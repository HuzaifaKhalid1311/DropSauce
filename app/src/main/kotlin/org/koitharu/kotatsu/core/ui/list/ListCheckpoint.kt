package org.koitharu.kotatsu.core.ui.list

import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ErrorState
import org.koitharu.kotatsu.list.ui.model.LoadingState
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
	private var button: ListCheckpointView? = null
	private var onDisableRequested: (() -> Unit)? = null
	private var onLoadMore: (() -> Unit)? = null
	private var onRevealHidden: ((Long) -> Boolean)? = null

	/** Set while waiting for list content to arrive, so the decision is made on a filled list. */
	private var isArmed = false

	/** Non-null while paging towards a tapped checkpoint. */
	private var pendingTarget: Checkpoint? = null
	private var pageRequests = 0
	private var lastSnapshot: List<*>? = null

	private val scrollListener = object : RecyclerView.OnScrollListener() {
		override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
			if (dy != 0 && pendingTarget == null) {
				hide()
			}
		}

		override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
			if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
				pendingTarget = null
				hide()
			}
		}
	}

	fun attach(
		recyclerView: RecyclerView,
		button: ListCheckpointView?,
		onLoadMore: () -> Unit,
		onDisableRequested: () -> Unit,
		onRevealHidden: (mangaId: Long) -> Boolean,
	) {
		this.recyclerView = recyclerView
		this.button = button
		this.onLoadMore = onLoadMore
		this.onRevealHidden = onRevealHidden
		this.onDisableRequested = onDisableRequested
	}

	fun detach() {
		if (isOwner()) {
			button?.let {
				it.hide(immediate = true)
				it.tag = null
				it.onJumpClick = null
				it.onDisableClick = null
			}
		}
		recyclerView?.removeOnScrollListener(scrollListener)
		isArmed = false
		pendingTarget = null
		lastSnapshot = null
		button = null
		recyclerView = null
	}

	private fun isOwner() = button?.tag === this

	/** Persists the topmost visible manga row and how far it was scrolled past. */
	fun save() {
		val rv = recyclerView ?: return
		val lm = rv.layoutManager as? LinearLayoutManager ?: return
		val firstPosition = lm.findFirstVisibleItemPosition()
		if (firstPosition == RecyclerView.NO_POSITION) {
			return
		}
		if (firstPosition < MIN_DISTANCE) {
			settings.setListCheckpoint(scope, "")
			return
		}
		val items = items() ?: return
		val lastPosition = lm.findLastVisibleItemPosition().coerceAtLeast(firstPosition)
		var targetPos = -1
		var targetId = 0L
		for (pos in firstPosition..lastPosition) {
			val candidate = items.getOrNull(pos) as? MangaListModel
			if (candidate != null) {
				targetPos = pos
				targetId = candidate.id
				break
			}
		}
		if (targetPos < 0) {
			settings.setListCheckpoint(scope, "") // only folded headers on screen: an older spot would mislead
			return
		}
		val offset = (lm.findViewByPosition(targetPos)?.top ?: 0) - rv.paddingTop
		settings.setListCheckpoint(scope, Checkpoint(targetId, targetPos, offset).encode())
	}

	fun onResume() {
		button?.let {
			it.tag = this
			it.onJumpClick = ::jump
			it.onDisableClick = { onDisableRequested?.invoke() }
			// Whatever the previous screen left on show is not ours to keep.
			it.hide(immediate = true)
		}
		isArmed = true
		pendingTarget = null
		lastSnapshot = null
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
		val snapshot = items()
		if (indexOf(target.mangaId) < 0 && snapshot == lastSnapshot) {
			// The list stopped growing: the row is gone, unless it was only folded away.
			if (onRevealHidden?.invoke(target.mangaId) == true) {
				lastSnapshot = snapshot
			} else {
				pendingTarget = null
				hide()
			}
			return
		}
		scrollOrLoad(target, fromTap = false)
	}

	private fun tryShow() {
		if (!isArmed) {
			return
		}
		val button = button ?: return
		val items = items() ?: return
		if (items.isEmpty() || items.any { it is LoadingState }) {
			return // not loaded yet, stay armed
		}
		isArmed = false
		// No rows at all (empty or error) means nothing to jump to; headers alone are folded rows
		if (items.any { it is EmptyState || it is ErrorState }) return
		val checkpoint = checkpoint() ?: return
		if (!settings.isListCheckpointEnabled || checkpoint.index < MIN_DISTANCE) {
			return
		}
		// If it is already loaded and near the top there is nowhere worth jumping to.
		val loadedAt = indexOf(checkpoint.mangaId)
		if (loadedAt in 0 until MIN_DISTANCE) {
			return
		}
		button.show()
		recyclerView?.removeOnScrollListener(scrollListener)
		recyclerView?.addOnScrollListener(scrollListener)
	}

	fun hide() {
		isArmed = false
		pendingTarget = null
		recyclerView?.removeOnScrollListener(scrollListener)
		button?.hide()
	}

	private fun jump() {
		val checkpoint = checkpoint() ?: run {
			hide()
			return
		}
		pageRequests = 0
		scrollOrLoad(checkpoint, fromTap = true)
	}

	/** Scrolls to the checkpoint, pulling in further pages first if it is not loaded yet. */
	private fun scrollOrLoad(checkpoint: Checkpoint, fromTap: Boolean) {
		val position = indexOf(checkpoint.mangaId)
		if (position >= 0) {
			val wasPaging = !fromTap
			pendingTarget = null
			(recyclerView?.layoutManager as? LinearLayoutManager)
				?.scrollToPositionWithOffset(position, checkpoint.offset)
			if (wasPaging) {
				recyclerView?.hapticFeedback(HapticEffect.LIGHT_CLICK)
			}
			hide()
			return
		}
		if (onRevealHidden?.invoke(checkpoint.mangaId) == true) {
			pendingTarget = checkpoint // loaded but folded away: scroll once it shows
			lastSnapshot = items()
			button?.setTravelling()
			return
		}
		if (pageRequests >= MAX_PAGE_REQUESTS) {
			pendingTarget = null
			hide()
			return
		}
		pendingTarget = checkpoint
		button?.setTravelling()
		val snapshot = items()
		val count = snapshot?.size ?: 0
		lastSnapshot = snapshot
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
				val parts = raw?.lineSequence()?.firstOrNull()?.split(':') ?: return null
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
	}
}
