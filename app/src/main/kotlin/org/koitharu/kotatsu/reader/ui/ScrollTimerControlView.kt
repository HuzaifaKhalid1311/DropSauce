package org.koitharu.kotatsu.reader.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.transition.Slide
import androidx.transition.TransitionManager
import com.google.android.material.slider.LabelFormatter
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.parentView
import org.koitharu.kotatsu.databinding.ViewScrollTimerBinding
import java.text.NumberFormat
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class ScrollTimerControlView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null
) : ConstraintLayout(context, attrs), CompoundButton.OnCheckedChangeListener, Slider.OnChangeListener,
	View.OnClickListener, LabelFormatter {

	@Inject
	lateinit var settings: AppSettings

	var onVisibilityChangeListener: OnVisibilityChangeListener? = null

	private val binding = ViewScrollTimerBinding.inflate(LayoutInflater.from(context), this)

	private var scrollTimer: ScrollTimer? = null
	private val percentFormat = NumberFormat.getPercentInstance()
	private var readerMode: ReaderMode = ReaderMode.STANDARD
	private var isApplyingSliderMode = false

	private val isScrollingMode: Boolean
		get() = readerMode == ReaderMode.WEBTOON

	init {
		binding.switchScrollTimer.setOnCheckedChangeListener(this)
		binding.sliderTimer.addOnChangeListener(this)
		binding.buttonFab.setOnClickListener(this)
		binding.sliderTimer.setLabelFormatter(this)
		binding.buttonClose.setOnClickListener(this)
		binding.buttonFab.isGone = resources.getBoolean(R.bool.is_tablet)
	}

	fun attach(timer: ScrollTimer, lifecycleOwner: LifecycleOwner) {
		scrollTimer = timer
		timer.isActive.observe(lifecycleOwner) {
			binding.switchScrollTimer.setOnCheckedChangeListener(null)
			binding.switchScrollTimer.isChecked = it
			binding.switchScrollTimer.setOnCheckedChangeListener(this)
		}
		settings.observeAsStateFlow(
			scope = lifecycleOwner.lifecycleScope + Dispatchers.Default,
			key = AppSettings.KEY_READER_AUTOSCROLL_FAB,
			valueProducer = { isReaderAutoscrollFabVisible },
		).observe(lifecycleOwner) {
			binding.buttonFab.isChecked = it
		}
		applySliderMode()
	}

	fun onReaderModeChanged(mode: ReaderMode) {
		if (readerMode == mode) {
			return
		}
		readerMode = mode
		applySliderMode()
	}

	/**
	 * The slider means different things per reader mode: a scroll speed in webtoon, and the dwell
	 * time on each page in the paged modes, where nothing scrolls and only the page flip is timed.
	 */
	private fun applySliderMode() {
		val slider = binding.sliderTimer
		isApplyingSliderMode = true
		// Widen to a range covering both modes first: narrowing the bounds while the current value
		// sits outside them makes Slider throw.
		slider.stepSize = 0f
		slider.valueFrom = SPEED_MIN
		slider.valueTo = PAGE_DELAY_MAX
		if (isScrollingMode) {
			slider.value = settings.readerAutoscrollSpeed.coerceIn(SPEED_MIN, SPEED_MAX)
			slider.valueTo = SPEED_MAX
			binding.labelTimer.setText(R.string.speed)
		} else {
			slider.value = settings.readerAutoscrollPageDelay.toFloat()
				.coerceIn(PAGE_DELAY_MIN, PAGE_DELAY_MAX)
			slider.valueFrom = PAGE_DELAY_MIN
			slider.stepSize = PAGE_DELAY_STEP
			binding.labelTimer.setText(R.string.interval)
		}
		isApplyingSliderMode = false
		updateDescription()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_close -> hide()
			R.id.button_fab -> settings.isReaderAutoscrollFabVisible = !settings.isReaderAutoscrollFabVisible
		}
	}

	override fun getFormattedValue(value: Float): String = if (isScrollingMode) {
		percentFormat.format(((value - SPEED_MIN) / (SPEED_MAX - SPEED_MIN)).coerceIn(0f, 1f))
	} else {
		context.getString(R.string.seconds_short, value.roundToInt())
	}

	override fun onValueChange(
		slider: Slider,
		value: Float,
		fromUser: Boolean
	) {
		if (isApplyingSliderMode) {
			return
		}
		if (fromUser) {
			if (isScrollingMode) {
				settings.readerAutoscrollSpeed = value
			} else {
				settings.readerAutoscrollPageDelay = value.roundToInt()
			}
		}
		updateDescription()
	}

	override fun onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean) {
		scrollTimer?.setActive(isChecked)
	}

	override fun setVisibility(visibility: Int) {
		super.setVisibility(visibility)
		onVisibilityChangeListener?.onVisibilityChanged(this, visibility)
	}

	fun show() {
		setupVisibilityTransition()
		isVisible = true
	}

	fun hide() {
		setupVisibilityTransition()
		isVisible = false
	}

	fun showOrHide() {
		setupVisibilityTransition()
		isVisible = !isVisible
	}

	private fun setupVisibilityTransition() {
		if (context.isAnimationsEnabled) {
			val sceneRoot = parentView ?: return
			val transition = Slide()
			transition.addTarget(this)
			TransitionManager.beginDelayedTransition(sceneRoot, transition)
		}
	}

	private fun updateDescription() {
		binding.textViewValue.text = getFormattedValue(binding.sliderTimer.value)
		if (isScrollingMode) {
			binding.textViewDescription.isVisible = false
		} else {
			binding.textViewDescription.text = context.getString(
				R.string.page_switch_timer,
				binding.sliderTimer.value.roundToInt(),
			)
			binding.textViewDescription.isVisible = true
		}
	}

	fun interface OnVisibilityChangeListener {

		fun onVisibilityChanged(v: View, visibility: Int)
	}

	private companion object {

		const val SPEED_MIN = 0.01f
		const val SPEED_MAX = 1f
		const val PAGE_DELAY_MIN = 1f
		const val PAGE_DELAY_MAX = 10f
		const val PAGE_DELAY_STEP = 1f
	}
}
