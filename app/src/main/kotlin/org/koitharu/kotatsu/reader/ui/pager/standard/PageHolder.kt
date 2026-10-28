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

	override fun splitSide(data: ReaderPage): PageViewModel.SplitSide? = when {
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
				binding.ssiv.resetScaleAndCenter()
			}

			ZoomMode.FIT_HEIGHT -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
				binding.ssiv.minScale = binding.ssiv.height / binding.ssiv.shownHeight.toFloat()
				binding.ssiv.setScaleAndCenter(
					binding.ssiv.minScale,
					PointF(0f, binding.ssiv.shownHeight / 2f),
				)
			}

			ZoomMode.FIT_WIDTH -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
				binding.ssiv.minScale = binding.ssiv.width / binding.ssiv.shownWidth.toFloat()
				binding.ssiv.setScaleAndCenter(
					binding.ssiv.minScale,
					PointF(binding.ssiv.shownWidth / 2f, 0f),
				)
			}

			ZoomMode.KEEP_START -> {
				binding.ssiv.minimumScaleType = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
				binding.ssiv.setScaleAndCenter(
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
		if (viewModel.isSplit && !page.isSecondHalf) {
			(bindingAdapter as? BaseReaderAdapter<*>)?.onPageSplit(page)
		}
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
