package org.koitharu.kotatsu.tracker.ui.feed.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.animation.PathInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getItem
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.tanh

/**
 * Swipe right → delete a single feed entry, swipe left → mark it as read.
 * The reveal fills the row's full height and width with a rounded (stadium) pill + centered icon and
 * fires a haptic tick when the action threshold is crossed. An already-read entry can't be marked
 * read: its swipe rubber-bands to a shallow point and greys out, and the action never fires.
 *
 * Mark-as-read never "commits" the swipe in ItemTouchHelper's sense (a commit means the row is
 * going away, and re-inserting it fights the recover animation, leaving the row stuck mid-flight).
 * Instead the left swipe is undismissable and the action fires on release once the drag has passed
 * the threshold, so the row always settles back with the standard smooth animation.
 */
class FeedSwipeCallback(
	context: Context,
	private val onAction: (item: FeedItem, isRead: Boolean) -> Unit,
) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {

	private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

	private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.ic_delete)?.mutate()
	private val readIcon = ContextCompat.getDrawable(context, R.drawable.ic_eye_check)?.mutate()

	// fixed colors on purpose: the reveal should look identical in every theme, light or dark
	private val deleteBg = 0xFFD32F2F.toInt()
	private val readBg = 0xFF1976D2.toInt()
	private val deleteIconTint = Color.WHITE
	private val readIconTint = Color.WHITE
	private val disabledBg = 0xFF757575.toInt()
	private val disabledIconTint = 0xFFE0E0E0.toInt()

	private var hapticFired = false
	private var rejectHapticFired = false
	private var lastDx = 0f
	private var isCurrentItemRead = false
	private var currentItem: FeedItem? = null
	private var pendingMarkRead = false

	/** Where each released row started settling back from. */
	private val settleStart = HashMap<RecyclerView.ViewHolder, Float>()

	override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
		// only feed rows are swipeable, not the date headers
		if (viewHolder.itemViewType != ListItemType.FEED.ordinal) {
			return 0
		}
		return super.getMovementFlags(recyclerView, viewHolder)
	}

	override fun onMove(
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		target: RecyclerView.ViewHolder,
	): Boolean = false

	override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float {
		// A leftward swipe (mark read) never commits — the action fires on release instead,
		// letting the row settle back with the standard recover animation.
		return if (lastDx < 0f) 3f else 0.4f
	}

	// a fling can commit a swipe via escape velocity even below the distance threshold, which made
	// a short flick delete a row; only dragging past the threshold (where the haptic tick fires) counts
	override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE

	override fun getSwipeVelocityThreshold(defaultValue: Float): Float = Float.MAX_VALUE

	override fun getAnimationDuration(
		recyclerView: RecyclerView,
		animationType: Int,
		animateDx: Float,
		animateDy: Float,
	): Long = when (animationType) {
		ItemTouchHelper.ANIMATION_TYPE_SWIPE_SUCCESS -> DELETE_ANIMATION_DURATION_MS
		// the default borrows the list's 120ms remove animation, which made the row snap back
		ItemTouchHelper.ANIMATION_TYPE_SWIPE_CANCEL -> SETTLE_ANIMATION_DURATION_MS
		else -> super.getAnimationDuration(recyclerView, animationType, animateDx, animateDy)
	}

	override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
		super.onSelectedChanged(viewHolder, actionState)
		if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && viewHolder != null) {
			currentItem = viewHolder.getItem(FeedItem::class.java)
			isCurrentItemRead = currentItem?.isNew == false
		} else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
			// finger lifted: fire mark-as-read if the drag was past the threshold at release
			if (pendingMarkRead) {
				currentItem?.let { onAction(it, true) }
			}
			pendingMarkRead = false
		}
	}

	override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
		// only delete can commit; mark-as-read is handled on release in onSelectedChanged
		if (direction != ItemTouchHelper.LEFT) {
			viewHolder.getItem(FeedItem::class.java)?.let { onAction(it, false) }
		}
	}

	override fun onChildDraw(
		c: Canvas,
		recyclerView: RecyclerView,
		viewHolder: RecyclerView.ViewHolder,
		dX: Float,
		dY: Float,
		actionState: Int,
		isCurrentlyActive: Boolean,
	) {
		val view = viewHolder.itemView
		val dX = if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) easeSettle(viewHolder, dX, isCurrentlyActive) else dX
		var effectiveDx = dX
		if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
			lastDx = dX
			val isRead = dX < 0f
			// snapshot, not the live item: after a committed mark-read the row rebinds with
			// isNew=false while this holder is still animating, and re-querying would flip the
			// visual into the grey "rejected" state mid-flight
			val isAlreadyRead = isRead && isCurrentItemRead
			if (isAlreadyRead) {
				// smooth rubber-band toward a shallow limit (tanh is monotonic with f(0)=0).
				// Applied both while dragging AND during the settle animation: ItemTouchHelper's
				// recover animation interpolates from its internal *raw* dX (not the clamped
				// translation), so leaving the settle path unclamped makes the row jump out to the
				// raw offset on release and then vanish. Clamping unconditionally keeps the visual
				// position continuous from drag through settle.
				val limit = view.width * 0.22f
				effectiveDx = -(limit * tanh(abs(dX) / limit))
				if (isCurrentlyActive) {
					// error buzz once the user pushes clearly past the rubber-band limit
					if (abs(dX) > limit * 2f && !rejectHapticFired) {
						view.hapticFeedback(HapticEffect.REJECT)
						rejectHapticFired = true
					}
				}
			}
			bgPaint.color = when {
				isAlreadyRead -> disabledBg
				isRead -> readBg
				else -> deleteBg
			}
			val top = view.top.toFloat()
			val bottom = view.bottom.toFloat()
			val left: Float
			val right: Float
			if (isRead) {
				// swipe left reveals the right side
				left = view.right + effectiveDx
				right = view.right.toFloat()
			} else {
				// swipe right reveals the left side
				left = view.left.toFloat()
				right = view.left + effectiveDx
			}
			if (right - left > 0f) {
				// full-height stadium pill filling the revealed region
				val radius = (bottom - top) / 2f
				c.drawRoundRect(left, top, right, bottom, radius, radius, bgPaint)

				val icon = if (isRead) readIcon else deleteIcon
				if (icon != null && right - left >= icon.intrinsicWidth) {
					val progress = (abs(effectiveDx) / view.width).coerceIn(0f, 1f)
					icon.alpha = ((progress * 3f).coerceAtMost(1f) * 255).toInt()
					icon.setTint(
						when {
							isAlreadyRead -> disabledIconTint
							isRead -> readIconTint
							else -> deleteIconTint
						},
					)
					val cx = ((left + right) / 2f).toInt()
					val cy = ((top + bottom) / 2f).toInt()
					icon.setBounds(
						cx - icon.intrinsicWidth / 2,
						cy - icon.intrinsicHeight / 2,
						cx + icon.intrinsicWidth / 2,
						cy + icon.intrinsicHeight / 2,
					)
					icon.draw(c)
				}
			}

			if (!isAlreadyRead) {
				val passedThreshold = abs(dX) >= 0.4f * view.width
				if (isCurrentlyActive) {
					if (isRead) {
						pendingMarkRead = passedThreshold
					}
					if (passedThreshold && !hapticFired) {
						view.hapticFeedback(HapticEffect.CONFIRM)
						hapticFired = true
					} else if (!passedThreshold) {
						hapticFired = false
					}
				}
			}
		}
		super.onChildDraw(c, recyclerView, viewHolder, effectiveDx, dY, actionState, isCurrentlyActive)
	}

	/**
	 * ItemTouchHelper's settle animation can't be given an interpolator, and its own is
	 * accelerate-decelerate. Recover its linear progress from the offset it hands us and re-ease it.
	 */
	private fun easeSettle(viewHolder: RecyclerView.ViewHolder, dX: Float, isCurrentlyActive: Boolean): Float {
		if (isCurrentlyActive) {
			settleStart.remove(viewHolder) // grabbed again mid-settle
			return dX
		}
		val start = settleStart.getOrPut(viewHolder) { dX }
		if (start == 0f) return dX
		val fraction = 1f - dX / start
		// a committed delete moves away from 0, not back to it
		if (fraction !in 0f..1f) return dX
		val linear = 1f - acos(2f * fraction - 1f) / PI.toFloat()
		return start * (1f - SETTLE_INTERPOLATOR.getInterpolation(linear))
	}

	override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
		super.clearView(recyclerView, viewHolder)
		settleStart.remove(viewHolder)
		hapticFired = false
		rejectHapticFired = false
		lastDx = 0f
		isCurrentItemRead = false
		// currentItem/pendingMarkRead are reset in onSelectedChanged(IDLE) at release; resetting
		// them here too would clobber a new gesture that starts while this row is still settling
	}

	private companion object {
		const val DELETE_ANIMATION_DURATION_MS = 200L
		const val SETTLE_ANIMATION_DURATION_MS = 450L
		val SETTLE_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f) // M3 standard easing
	}
}
