package org.koitharu.kotatsu.core.ui.sheet

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import androidx.annotation.AttrRes
import androidx.annotation.StringRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.withStyledAttributes
import androidx.core.view.ancestors
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetBehavior
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.LayoutSheetHeaderAdaptiveBinding
import kotlin.math.abs
import kotlin.math.roundToInt

class AdaptiveSheetHeaderBar @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	@AttrRes defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr), AdaptiveSheetCallback {

	private val binding =
		LayoutSheetHeaderAdaptiveBinding.inflate(LayoutInflater.from(context), this)
	private var sheetBehavior: AdaptiveSheetBehavior? = null
	private var dragHandleFullHeight = 0

	// Manual drag state — only active when behavior.isDraggable == false
	private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
	private var dragStartRawY = 0f
	private var sheetStartTop = 0
	private var interceptStartY = 0f
	private var isIntercepting = false
	private var cachedSheetView: View? = null
	private var cachedParentHeight = 0
	private var velocityTracker: VelocityTracker? = null

	var title: CharSequence?
		get() = binding.shTextViewTitle.text
		set(value) {
			binding.shTextViewTitle.text = value
		}

	init {
		orientation = VERTICAL
		binding.shButtonClose.setOnClickListener { dismissSheet() }
		context.withStyledAttributes(
			attrs,
			R.styleable.AdaptiveSheetHeaderBar, defStyleAttr,
		) {
			title = getText(R.styleable.AdaptiveSheetHeaderBar_title)
		}
	}

	override fun onAttachedToWindow() {
		super.onAttachedToWindow()
		if (isInEditMode) {
			val isTabled = resources.getBoolean(R.bool.is_tablet)
			binding.shDragHandle.isGone = isTabled
			binding.shLayoutSidesheet.isVisible = isTabled
		} else {
			setBottomSheetBehavior(findParentSheetBehavior())
		}
	}

	override fun onDetachedFromWindow() {
		cachedSheetView = null
		cachedParentHeight = 0
		velocityTracker?.recycle()
		velocityTracker = null
		setBottomSheetBehavior(null)
		super.onDetachedFromWindow()
	}

	override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
		val behavior = sheetBehavior as? AdaptiveSheetBehavior.Bottom ?: return super.onInterceptTouchEvent(ev)
		if (behavior.isDraggable) return super.onInterceptTouchEvent(ev)

		when (ev.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				isIntercepting = false
				interceptStartY = ev.rawY
				val sv = findOrGetSheetView()
				if (sv != null) {
					dragStartRawY = ev.rawY
					sheetStartTop = sv.top
					cachedParentHeight = (sv.parent as? View)?.height ?: cachedParentHeight
				}
				velocityTracker?.recycle()
				velocityTracker = VelocityTracker.obtain().also { it.addMovement(ev) }
			}
			MotionEvent.ACTION_MOVE -> {
				velocityTracker?.addMovement(ev)
				if (!isIntercepting && abs(ev.rawY - interceptStartY) > touchSlop) {
					isIntercepting = true
					return true
				}
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				if (!isIntercepting) {
					velocityTracker?.recycle()
					velocityTracker = null
				}
				isIntercepting = false
			}
		}
		return false
	}

	override fun onTouchEvent(event: MotionEvent): Boolean {
		val behavior = sheetBehavior as? AdaptiveSheetBehavior.Bottom ?: return super.onTouchEvent(event)
		if (behavior.isDraggable) return super.onTouchEvent(event)

		val sv = cachedSheetView ?: return true
		val parentHeight = cachedParentHeight.takeIf { it > 0 } ?: return true

		velocityTracker?.addMovement(event)

		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				// Direct touch on empty area — children didn't consume it
				dragStartRawY = event.rawY
				sheetStartTop = sv.top
				velocityTracker?.recycle()
				velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
				return true
			}
			MotionEvent.ACTION_MOVE -> {
				val dy = (event.rawY - dragStartRawY).toInt()
				val newTop = (sheetStartTop + dy).coerceIn(0, parentHeight)
				sv.offsetTopAndBottom(newTop - sv.top)
				return true
			}
			MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
				val vt = velocityTracker
				vt?.computeCurrentVelocity(1000, 8000f)
				val yVel = vt?.yVelocity ?: 0f
				vt?.recycle()
				velocityTracker = null

				val halfExpandedOffset = (parentHeight * (1f - HALF_EXPANDED_RATIO)).toInt()
				val currentTop = sv.top

				val newState = when {
					yVel < -FLING_VELOCITY -> AdaptiveSheetBehavior.STATE_EXPANDED
					yVel > FLING_VELOCITY -> AdaptiveSheetBehavior.STATE_HIDDEN
					currentTop < halfExpandedOffset / 2 -> AdaptiveSheetBehavior.STATE_EXPANDED
					currentTop > halfExpandedOffset + (parentHeight - halfExpandedOffset) / 2 -> AdaptiveSheetBehavior.STATE_HIDDEN
					else -> BottomSheetBehavior.STATE_HALF_EXPANDED
				}
				settleToState(sv, behavior, newState)
				isIntercepting = false
				return true
			}
		}
		return super.onTouchEvent(event)
	}

	override fun onGenericMotionEvent(event: MotionEvent): Boolean {
		val behavior = sheetBehavior ?: return super.onGenericMotionEvent(event)
		if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0) {
			if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
				if (event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0f) {
					behavior.state = if (
						behavior is AdaptiveSheetBehavior.Bottom
						&& behavior.state == AdaptiveSheetBehavior.STATE_EXPANDED
					) {
						AdaptiveSheetBehavior.STATE_COLLAPSED
					} else {
						AdaptiveSheetBehavior.STATE_HIDDEN
					}
				} else {
					behavior.state = AdaptiveSheetBehavior.STATE_EXPANDED
				}
				return true
			}
		}
		return super.onGenericMotionEvent(event)
	}

	override fun onStateChanged(sheet: View, newState: Int) {

	}

	fun setTitle(@StringRes resId: Int) {
		binding.shTextViewTitle.setText(resId)
	}

	/**
	 * Collapses the bottom-sheet drag handle as the sheet approaches full screen: `0f` keeps it at full
	 * height, `1f` shrinks it to nothing (height + alpha). Driven continuously from the sheet's slide
	 * offset, the handle melts away as part of the same upward motion instead of snapping out in a second
	 * step once full screen is reached. No-op on side sheets, which never show a drag handle.
	 */
	fun setDragHandleCollapseProgress(progress: Float) {
		if (sheetBehavior !is AdaptiveSheetBehavior.Bottom) {
			return
		}
		val handle = binding.shDragHandle
		val fullHeight = dragHandleFullHeight.takeIf { it > 0 }
			?: handle.height.takeIf { it > 0 }?.also { dragHandleFullHeight = it }
			?: return
		val clamped = progress.coerceIn(0f, 1f)
		val target = (fullHeight * (1f - clamped)).roundToInt()
		if (handle.layoutParams.height != target) {
			handle.updateLayoutParams { height = target }
		}
		handle.alpha = 1f - clamped
	}

	/**
	 * Drives [setDragHandleCollapseProgress] from a sheet's raw slide offset, so every sheet that
	 * expands to full screen melts its handle away over the same top stretch of the drag.
	 */
	fun setDragHandleCollapseFromSlide(slideOffset: Float) {
		setDragHandleCollapseProgress(
			(slideOffset - DRAG_HANDLE_COLLAPSE_START) / (1f - DRAG_HANDLE_COLLAPSE_START),
		)
	}

	private fun settleToState(sv: View, behavior: AdaptiveSheetBehavior.Bottom, newState: Int) {
		if (behavior.state == newState) {
			// Behavior setter is a no-op when state hasn't changed — animate back manually
			val halfOffset = (cachedParentHeight * (1f - HALF_EXPANDED_RATIO)).toInt()
			val targetTop = when (newState) {
				AdaptiveSheetBehavior.STATE_EXPANDED -> 0
				BottomSheetBehavior.STATE_HALF_EXPANDED -> halfOffset
				else -> cachedParentHeight
			}
			val startTop = sv.top
			if (startTop == targetTop) return
			val range = targetTop - startTop
			var prevValue = 0
			ValueAnimator.ofInt(0, range).apply {
				duration = 200
				interpolator = DecelerateInterpolator()
				addUpdateListener { anim ->
					val value = anim.animatedValue as Int
					sv.offsetTopAndBottom(value - prevValue)
					prevValue = value
				}
				start()
			}
		} else {
			behavior.state = newState
		}
	}

	private fun findOrGetSheetView(): View? {
		val cached = cachedSheetView
		if (cached != null && cached.isAttachedToWindow) return cached
		return ancestors.firstNotNullOfOrNull { ancestor ->
			(ancestor as? View)?.takeIf { v ->
				(v.layoutParams as? CoordinatorLayout.LayoutParams)?.behavior is BottomSheetBehavior<*>
			}
		}.also { cachedSheetView = it }
	}

	private fun setBottomSheetBehavior(behavior: AdaptiveSheetBehavior?) {
		binding.shDragHandle.isVisible = behavior is AdaptiveSheetBehavior.Bottom
		binding.shLayoutSidesheet.isVisible = behavior is AdaptiveSheetBehavior.Side
		sheetBehavior?.removeCallback(this)
		sheetBehavior = behavior
		behavior?.addCallback(this)
	}

	private fun dismissSheet() {
		sheetBehavior?.state = AdaptiveSheetBehavior.STATE_HIDDEN
	}

	private fun findParentSheetBehavior(): AdaptiveSheetBehavior? {
		return ancestors.firstNotNullOfOrNull {
			((it as? View)?.layoutParams as? CoordinatorLayout.LayoutParams)
				?.let { params -> AdaptiveSheetBehavior.from(params) }
		}
	}

	private companion object {
		const val FLING_VELOCITY = 1000f  // pixels/second
		const val DRAG_HANDLE_COLLAPSE_START = 0.65f
		const val HALF_EXPANDED_RATIO = 0.5f
	}
}
