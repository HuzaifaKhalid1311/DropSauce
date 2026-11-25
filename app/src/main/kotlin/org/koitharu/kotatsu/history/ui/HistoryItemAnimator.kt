package org.koitharu.kotatsu.history.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.view.View
import androidx.interpolator.view.animation.FastOutLinearInInterpolator
import androidx.interpolator.view.animation.LinearOutSlowInInterpolator
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.list.ui.model.ListModel

/**
 * Folding a section: its rows sink away while the rows below already slide up, instead of
 * vanishing first and leaving a gap that closes afterwards. Unfolding grows them back in the same way.
 * Non-section updates (initial load, filtering, paging) fall back to [DefaultItemAnimator].
 */
class HistoryItemAnimator : DefaultItemAnimator() {

	private val running = HashSet<RecyclerView.ViewHolder>()
	private var isSectionTransition = false

	init {
		moveDuration = 300
	}

	fun onListDiffed(previousList: List<ListModel>, currentList: List<ListModel>) {
		if (previousList.isEmpty()) {
			isSectionTransition = false
			return
		}
		var sections: HashMap<String, Boolean>? = null
		for (item in previousList) {
			if (item is HistorySection) {
				(sections ?: HashMap<String, Boolean>().also { sections = it })[item.key] = item.isCollapsed
			}
		}
		val prevSections = sections
		isSectionTransition = prevSections != null && currentList.any {
			it is HistorySection && prevSections[it.key]?.let { wasCollapsed -> wasCollapsed != it.isCollapsed } == true
		}
	}

	override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
		if (!isSectionTransition) {
			return super.animateRemove(holder)
		}
		val view = holder.itemView
		endAnimation(holder)
		dispatchRemoveStarting(holder)
		run(holder, view.animate().alpha(0f).scaleX(SCALE).scaleY(SCALE).translationY(-view.height * SHIFT)
			.setDuration(REMOVE_DURATION).setInterpolator(FastOutLinearInInterpolator())) {
			dispatchRemoveFinished(holder)
		}
		return false
	}

	override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
		if (!isSectionTransition) {
			return super.animateAdd(holder)
		}
		val view = holder.itemView
		endAnimation(holder)
		view.alpha = 0f
		view.scaleX = SCALE
		view.scaleY = SCALE
		view.translationY = -view.height * SHIFT
		dispatchAddStarting(holder)
		run(holder, view.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
			.setStartDelay(ADD_DELAY).setDuration(ADD_DURATION).setInterpolator(LinearOutSlowInInterpolator())) {
			dispatchAddFinished(holder)
		}
		return false
	}

	private inline fun run(
		holder: RecyclerView.ViewHolder,
		animation: android.view.ViewPropertyAnimator,
		crossinline onDone: () -> Unit,
	) {
		running += holder
		animation.setListener(object : AnimatorListenerAdapter() {
			// Also called on cancel, which is how endAnimation() stops it.
			override fun onAnimationEnd(animator: Animator) {
				animation.setListener(null)
				animation.setStartDelay(0)
				holder.itemView.reset()
				if (running.remove(holder)) {
					onDone()
					if (!isRunning()) dispatchAnimationsFinished()
				}
			}
		}).start()
	}

	override fun endAnimation(item: RecyclerView.ViewHolder) {
		if (item in running) item.itemView.animate().cancel()
		super.endAnimation(item)
	}

	override fun endAnimations() {
		running.toList().forEach { it.itemView.animate().cancel() }
		super.endAnimations()
	}

	override fun isRunning() = running.isNotEmpty() || super.isRunning()

	private fun View.reset() {
		alpha = 1f
		scaleX = 1f
		scaleY = 1f
		translationY = 0f
	}

	private companion object {

		const val SCALE = 0.9f
		const val SHIFT = 0.25f
		const val REMOVE_DURATION = 200L
		const val ADD_DURATION = 260L
		const val ADD_DELAY = 60L
	}
}
