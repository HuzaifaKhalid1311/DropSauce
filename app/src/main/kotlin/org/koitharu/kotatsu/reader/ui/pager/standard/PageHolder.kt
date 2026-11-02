package org.koitharu.kotatsu.reader.ui.pager.standard

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.Build
import android.view.Gravity
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.setMargins
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import com.davemorrissey.labs.subscaleview.ImageViewState
import com.davemorrissey.labs.subscaleview.OnAnimationEventListener
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.ZoomMode
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.widgets.ZoomControl
import org.koitharu.kotatsu.databinding.ItemPageBinding
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.BasePageHolder
import org.koitharu.kotatsu.reader.ui.pager.BaseReaderAdapter
import org.koitharu.kotatsu.reader.ui.pager.ReaderPage
import org.koitharu.kotatsu.reader.ui.pager.vm.PageViewModel

private const val STANDARD_ZOOM_DURATION = 500
private const val FILL_ZOOM_DURATION = 250
private const val SPREAD_ZOOM_DURATION = 350L

open class PageHolder(
	owner: LifecycleOwner,
	binding: ItemPageBinding,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
) : BasePageHolder<ItemPageBinding>(
	binding = binding,
	loader = loader,
	readerSettingsProducer = readerSettingsProducer,
	networkState = networkState,
	exceptionResolver = exceptionResolver,
	lifecycleOwner = owner,
), ZoomControl.ZoomControlListener, OnApplyWindowInsetsListener {

	override val ssiv = binding.ssiv

	/** Which half of a split wide page is read first. */
	protected open val isFirstHalfRight = false

	/** Wide pages aren't split where pages already sit side by side. */
	protected open val isSplitSupported = true

	/** The split page the reader's spread button shows whole; it goes back to its half once left. */
	private var wholePage: ReaderPage? = null

	/** Toggling a split page reloads it: it opens at [spreadStart] and, with [isZoomingToRest], glides to its zoom. */
	private var spreadStart: ImageViewState? = null
	private var isZoomingToRest = false

	/** Already gliding back into the half; repeated "page left" calls leave it running. */
	private var isReturningToHalf = false

	/** For the reader's spread button: null when this isn't a split page, true while it's shown whole. */
	val spreadState: Boolean?
		get() = when {
			wholePage != null && wholePage == boundData -> true
			viewModel.isSplit -> false
			else -> null
		}

	/** [animate] zooms between the half and the whole page like a double tap instead of jumping. */
	fun showWhole(isWhole: Boolean, animate: Boolean = false) {
		val data = boundData ?: return
		val target = if (isWhole) data else null
		if (wholePage == target || (!isWhole && isReturningToHalf)) return
		val ssiv = binding.ssiv
		val center = ssiv.getCenter()
		if (!animate || !ssiv.isReady || center == null) {
			wholePage = target
			bind(data)
			return
		}
		if (isWhole) {
			// Open the whole page exactly where the half was, then zoom out from there.
			val offset = if (viewModel.splitSide == PageViewModel.SplitSide.RIGHT) ssiv.sWidth else 0
			spreadStart = ImageViewState(ssiv.scale, center.x + offset, center.y, ssiv.orientation)
			isZoomingToRest = true
			wholePage = target
			holdFrame()
			bind(data)
			return
		}
		// Zoom into the half first, then swap back to it at the same spot.
		val leftWidth = ssiv.sWidth / 2
		val isRight = splitSideIgnoringWhole(data) == PageViewModel.SplitSide.RIGHT
		val halfWidth = if (isRight) ssiv.sWidth - leftWidth else leftWidth
		val scale = minOf(ssiv.width / halfWidth.toFloat(), ssiv.height / ssiv.sHeight.toFloat())
		val halfCenterX = (if (isRight) leftWidth else 0) + halfWidth / 2f
		isReturningToHalf = true
		val swap = {
			isReturningToHalf = false
			if (wholePage == data && boundData == data) {
				spreadStart = ImageViewState(scale, halfWidth / 2f, ssiv.sHeight / 2f, ssiv.orientation)
				wholePage = null
				holdFrame()
				bind(data)
			}
		}
		ssiv.animateScaleAndCenter(scale, PointF(halfCenterX, ssiv.sHeight / 2f))
			?.withDuration(SPREAD_ZOOM_DURATION)
			?.withInterpolator(DecelerateInterpolator())
			?.withOnAnimationEventListener(object : OnAnimationEventListener {
				override fun onComplete() = swap()
				override fun onInterruptedByUser() = swap()
				override fun onInterruptedByNewAnim() {
					isReturningToHalf = false
				}
			})
			?.start() ?: swap()
	}

	override fun takeInitialViewState(): ImageViewState? = spreadStart.also { spreadStart = null }

	/** Sets this page's resting zoom, or glides there when it was just toggled between half and whole. */
	protected fun SubsamplingScaleImageView.restAt(scale: Float, center: PointF) {
		releaseFrame()
		if (!isZoomingToRest) {
			setScaleAndCenter(scale, center)
			return
		}
		isZoomingToRest = false
		animateScaleAndCenter(scale, center)
			?.withDuration(SPREAD_ZOOM_DURATION)
			?.withInterpolator(DecelerateInterpolator())
			?.start()
	}

	init {
		ViewCompat.setOnApplyWindowInsetsListener(binding.root, this)
	}

	/** "Fill screen" zooms just until the page fills the screen; "Standard" keeps the library's own amount. */
	protected fun SubsamplingScaleImageView.applyDoubleTapZoom() {
		val isFill = settings.isDoubleTapFill
		if (isFill) {
			doubleTapZoomScale = maxScale / 2f
		} else {
			setDoubleTapZoomDpi(160)
		}
		// The smaller fill zoom looks sluggish over the library's 500 ms, so it runs faster.
		// shortcut: the library has no setter for this, swap for one if it ever gets it
		runCatching {
			SubsamplingScaleImageView::class.java.getDeclaredField("doubleTapZoomDuration").apply {
				isAccessible = true
				setInt(this@applyDoubleTapZoom, if (isFill) FILL_ZOOM_DURATION else STANDARD_ZOOM_DURATION)
			}
		}
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			insets.toWindowInsets()?.let {
				applyRoundedCorners(it)
			}
		}
		return insets
	}

	override fun pageOrientation() = when {
		!settings.isRotateWideEnabled || !viewModel.isWidePage -> SubsamplingScaleImageView.ORIENTATION_0
		settings.isRotateWideInverted -> SubsamplingScaleImageView.ORIENTATION_270
		else -> SubsamplingScaleImageView.ORIENTATION_90
	}

	override fun splitSide(data: ReaderPage): PageViewModel.SplitSide? =
		if (data == wholePage) null else splitSideIgnoringWhole(data)

	private fun splitSideIgnoringWhole(data: ReaderPage): PageViewModel.SplitSide? = when {
		!isSplitSupported || !settings.isSplitWideEnabled -> null
		data.isSecondHalf != isFirstHalfRight -> PageViewModel.SplitSide.RIGHT
		else -> PageViewModel.SplitSide.LEFT
	}

	override fun onConfigChanged(settings: ReaderSettings) {
		super.onConfigChanged(settings)
		binding.textViewNumber.isVisible = settings.isPagesNumbersEnabled
		// "Split wide pages" was switched: load the page again whole or halved
		val data = boundData
		if (data != null && splitSide(data) != viewModel.splitSide) {
			bind(data)
		}
	}

	@SuppressLint("SetTextI18n")
	override fun onBind(data: ReaderPage) {
		super.onBind(data)
		binding.textViewNumber.text = (data.index + 1).toString()
	}

	override fun onReady() {
		binding.ssiv.maxScale = 2f * maxOf(
			binding.ssiv.width / binding.ssiv.shownWidth.toFloat(),
			binding.ssiv.height / binding.ssiv.shownHeight.toFloat(),
		)
		binding.ssiv.applyDoubleTapZoom()
		binding.ssiv.colorFilter = settings.colorFilter?.toColorFilter()
		when (settings.zoomMode) {
			ZoomMode.FIT_CENTER -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
				binding.ssiv.restAt(binding.ssiv.minScale, PointF(binding.ssiv.sWidth / 2f, binding.ssiv.sHeight / 2f))
			}

			ZoomMode.FIT_HEIGHT -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
				binding.ssiv.minScale = binding.ssiv.height / binding.ssiv.shownHeight.toFloat()
				binding.ssiv.restAt(
					binding.ssiv.minScale,
					PointF(0f, binding.ssiv.shownHeight / 2f),
				)
			}

			ZoomMode.FIT_WIDTH -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
				binding.ssiv.minScale = binding.ssiv.width / binding.ssiv.shownWidth.toFloat()
				binding.ssiv.restAt(
					binding.ssiv.minScale,
					PointF(binding.ssiv.shownWidth / 2f, 0f),
				)
			}

			ZoomMode.KEEP_START -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
				binding.ssiv.restAt(
					binding.ssiv.maxScale,
					PointF(0f, 0f),
				)
			}
		}
		notifySplit()
	}

	/** Once a wide page shows its first half, the pager gets an extra item for the second one. */
	protected fun notifySplit() {
		val page = boundData ?: return
		val adapter = bindingAdapter as? BaseReaderAdapter<*> ?: return
		if (viewModel.isSplit && !page.isSecondHalf) {
			adapter.onPageSplit(page)
		}
		adapter.onPageShown?.invoke()
	}

	override fun onRecycled() {
		wholePage = null
		spreadStart = null
		isZoomingToRest = false
		isReturningToHalf = false
		super.onRecycled()
	}

	override fun onZoomIn() {
		scaleBy(1.2f)
	}

	override fun onZoomOut() {
		scaleBy(0.8f)
	}

	@SuppressLint("RtlHardcoded")
	@RequiresApi(Build.VERSION_CODES.S)
	protected open fun applyRoundedCorners(insets: WindowInsets) {
		binding.textViewNumber.updateLayoutParams<FrameLayout.LayoutParams> {
			val baseMargin = context.resources.getDimensionPixelOffset(R.dimen.margin_small)
			val absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection)
			val corner = when {
				absoluteGravity and Gravity.LEFT == Gravity.LEFT -> {
					insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)
				}

				absoluteGravity and Gravity.RIGHT == Gravity.RIGHT -> {
					insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)
				}

				else -> {
					null
				}
			}
			setMargins(baseMargin + (corner?.radius ?: 0))
		}
	}

	private fun scaleBy(factor: Float) {
		val ssiv = binding.ssiv
		val center = ssiv.getCenter() ?: return
		val newScale = ssiv.scale * factor
		ssiv.animateScaleAndCenter(newScale, center)?.apply {
			withDuration(ssiv.resources.getInteger(android.R.integer.config_shortAnimTime).toLong())
			withInterpolator(DecelerateInterpolator())
			start()
		}
	}
}
