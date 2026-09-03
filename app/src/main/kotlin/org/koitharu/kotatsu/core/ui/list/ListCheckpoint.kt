package org.koitharu.kotatsu.core.ui.list

import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.util.ext.applySystemAnimatorScale
import org.koitharu.kotatsu.list.ui.model.MangaListModel

/**
 * Remembers where the user was in a long list and offers a one-tap jump back to it.
 *
 * The spot is stored as the *manga id* of the topmost visible row plus its pixel offset, rather
 * than a scroll position: History reorders itself constantly, so an index alone would be pointing
 * at a different manga by the time the user comes back.
 *
 * The list is paginated, so after a cold start the remembered manga usually is not loaded yet.
 * The saved index is therefore kept as well — it decides whether to offer the jump at all — and
 * pages are pulled in on demand when the pill is actually tapped.
 *
 * The pill itself belongs to the host activity, so several instances of this class can share one
 * view. Ownership is claimed in [onResume] and kept in the view's tag.
 */
class ListCheckpoint(
	private val scope: String,
	private val settings: AppSettings,
) {

	private var recyclerView: RecyclerView? = null
	private var button: View? = null
	private var onDisableRequested: (() -> Unit)? = null
	private var onLoadMore: (() -> Unit)? = null

	/** Set while waiting for list content to arrive, so the decision is made on a filled list. */
	private var isArmed = false

	/** Non-null while paging towards a tapped checkpoint. */
	private var pendingTarget: Checkpoint? = null
	private var pageRequests = 0
	private var lastItemCount = 0

	private val scrollListener = object : RecyclerView.OnScrollListener() {
		override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
			if (dy != 0) {
				hide()
			}
		}
	}

	private val hideRunnable = Runnable { hide() }

	fun attach(
		recyclerView: RecyclerView,
		button: View?,
		onLoadMore: () -> Unit,
		onDisableRequested: () -> Unit,
	) {
		this.recyclerView = recyclerView
		this.button = button
		this.onLoadMore = onLoadMore
		this.onDisableRequested = onDisableRequested
	}

	fun detach() {
		if (isOwner()) {
			hide()
			button?.let {
				it.tag = null
				it.setOnClickListener(null)
				it.setOnLongClickListener(null)
			}
		}
		recyclerView?.removeOnScrollListener(scrollListener)
		isArmed = false
		pendingTarget = null
		button = null
		recyclerView = null
	}

	private fun isOwner() = button?.tag === this

	/** Persists the topmost visible row and how far it was scrolled past. */
	fun save() {
		val rv = recyclerView ?: return
		val lm = rv.layoutManager as? LinearLayoutManager ?: return
		val position = lm.findFirstVisibleItemPosition()
		if (position == RecyclerView.NO_POSITION) {
			return
		}
		val id = (items()?.getOrNull(position) as? MangaListModel)?.id ?: return
		val offset = (lm.findViewByPosition(position)?.top ?: 0) - rv.paddingTop
		settings.setListCheckpoint(scope, Checkpoint(id, position, offset).encode())
	}

	fun onResume() {
		button?.let {
			it.tag = this
			it.setOnClickListener { jump() }
			it.setOnLongClickListener { _ ->
				onDisableRequested?.invoke()
				true
			}
			// Whatever the previous screen left on show is not ours to keep.
			it.animate().cancel()
			it.isVisible = false
		}
		isArmed = true
		pendingTarget = null
		tryShow()
	}

	fun onPause() {
		save()
		if (isOwner()) {
			hide()
		}
	}

	/** Call whenever the list content changes — the checkpoint can only be resolved once loaded. */
	fun onContentChanged() {
		if (!isOwner()) {
			return
		}
		val target = pendingTarget
		if (target == null) {
			tryShow()
			return
		}
		val count = items()?.size ?: 0
		if (indexOf(target.mangaId) < 0 && count <= lastItemCount) {
			pendingTarget = null // the list stopped growing, so the row is simply gone
			return
		}
		scrollOrLoad(target)
	}

	private fun tryShow() {
		if (!isArmed) {
			return
		}
		val button = button ?: return
		val items = items() ?: return
		if (items.none { it is MangaListModel }) {
			return // not loaded yet, stay armed
		}
		isArmed = false
		val checkpoint = checkpoint() ?: return
		if (!settings.isListCheckpointEnabled || checkpoint.index < MIN_DISTANCE) {
			return
		}
		// If it is already loaded and near the top there is nowhere worth jumping to.
		val loadedAt = indexOf(checkpoint.mangaId)
		if (loadedAt in 0 until MIN_DISTANCE) {
			return
		}
		button.animate().cancel()
		button.isVisible = true
		button.alpha = 0f
		button.scaleX = 0.85f
		button.scaleY = 0.85f
		button.translationY = button.height.toFloat()
		button.animate()
			.alpha(1f)
			.scaleX(1f)
			.scaleY(1f)
			.translationY(0f)
			.setInterpolator(OvershootInterpolator(OVERSHOOT_TENSION))
			.setDuration(SHOW_DURATION_MS)
			.applySystemAnimatorScale(button.context)
			.start()
		button.removeCallbacks(hideRunnable)
		button.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS)
		recyclerView?.addOnScrollListener(scrollListener)
	}

	fun hide() {
		isArmed = false
		recyclerView?.removeOnScrollListener(scrollListener)
		val button = button ?: return
		button.removeCallbacks(hideRunnable)
		if (!button.isVisible) {
			return
		}
		button.animate().cancel()
		button.animate()
			.alpha(0f)
			.scaleX(0.85f)
			.scaleY(0.85f)
			.translationY(button.height.toFloat())
			.setInterpolator(null)
			.setDuration(HIDE_DURATION_MS)
			.applySystemAnimatorScale(button.context)
			.withEndAction { button.isVisible = false }
			.start()
	}

	private fun jump() {
		val checkpoint = checkpoint() ?: return
		hide()
		pageRequests = 0
		scrollOrLoad(checkpoint)
	}

	/** Scrolls to the checkpoint, pulling in further pages first if it is not loaded yet. */
	private fun scrollOrLoad(checkpoint: Checkpoint) {
		val position = indexOf(checkpoint.mangaId)
		if (position >= 0) {
			pendingTarget = null
			(recyclerView?.layoutManager as? LinearLayoutManager)
				?.scrollToPositionWithOffset(position, checkpoint.offset)
			return
		}
		if (pageRequests >= MAX_PAGE_REQUESTS) {
			pendingTarget = null
			return
		}
		pendingTarget = checkpoint
		val count = items()?.size ?: 0
		lastItemCount = count
		pageRequests++
		// Follow the list down as pages arrive instead of standing still until the row shows up,
		// so a far-away checkpoint reads as travelling there rather than as a frozen screen.
		if (count > 0) {
			(recyclerView?.layoutManager as? LinearLayoutManager)
				?.scrollToPositionWithOffset(minOf(checkpoint.index, count - 1), 0)
		}
		onLoadMore?.invoke()
	}

	private fun indexOf(mangaId: Long) = items()
		?.indexOfFirst { it is MangaListModel && it.id == mangaId } ?: -1

	private fun checkpoint() = Checkpoint.parse(settings.getListCheckpoint(scope))

	private fun items() = (recyclerView?.adapter as? BaseListAdapter<*>)?.items

	private data class Checkpoint(
		val mangaId: Long,
		val index: Int,
		val offset: Int,
	) {

		fun encode() = "$mangaId:$index:$offset"

		companion object {

			fun parse(raw: String?): Checkpoint? {
				val parts = raw?.split(':') ?: return null
				if (parts.size != 3) {
					return null
				}
				val mangaId = parts[0].toLongOrNull() ?: return null
				val index = parts[1].toIntOrNull() ?: return null
				val offset = parts[2].toIntOrNull() ?: return null
				return if (mangaId == 0L) null else Checkpoint(mangaId, index, offset)
			}
		}
	}

	companion object {

		/** Below this the checkpoint is close enough to the top that jumping would do nothing. */
		private const val MIN_DISTANCE = 6

		/** Bounds how far the list will page itself to reach a checkpoint. */
		private const val MAX_PAGE_REQUESTS = 48
		private const val SHOW_DURATION_MS = 300L
		private const val HIDE_DURATION_MS = 160L
		private const val AUTO_HIDE_DELAY_MS = 5000L
		private const val OVERSHOOT_TENSION = 1.6f
	}
}
