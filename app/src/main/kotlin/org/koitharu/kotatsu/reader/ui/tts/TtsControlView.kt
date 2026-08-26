package org.koitharu.kotatsu.reader.ui.tts

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.transition.Slide
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.parentView
import org.koitharu.kotatsu.databinding.ViewTtsControlBinding
import org.koitharu.kotatsu.reader.ui.ScrollTimerControlView
import javax.inject.Inject

/**
 * Transport + settings panel for [ReaderTts], built to match the automatic scroll panel: same card,
 * same slide-in, same close button.
 */
@AndroidEntryPoint
class TtsControlView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : ConstraintLayout(context, attrs), View.OnClickListener, Slider.OnChangeListener {

	@Inject
	lateinit var settings: AppSettings

	var onVisibilityChangeListener: ScrollTimerControlView.OnVisibilityChangeListener? = null

	private val binding = ViewTtsControlBinding.inflate(LayoutInflater.from(context), this)
	private var tts: ReaderTts? = null

	init {
		binding.buttonClose.setOnClickListener(this)
		binding.buttonPlay.setOnClickListener(this)
		binding.buttonNext.setOnClickListener(this)
		binding.buttonPrevious.setOnClickListener(this)
		binding.sliderSpeed.addOnChangeListener(this)
		binding.sliderPitch.addOnChangeListener(this)
	}

	fun attach(controller: ReaderTts, lifecycleOwner: LifecycleOwner) {
		tts = controller
		// Re-queue once, on release: doing it per tick would restart the sentence on every pixel.
		listOf(binding.sliderSpeed, binding.sliderPitch).forEach { it.addOnSliderTouchListener(tuningListener) }
		binding.sliderSpeed.value = settings.epubTtsSpeed.coerceIn(
			binding.sliderSpeed.valueFrom,
			binding.sliderSpeed.valueTo,
		)
		binding.sliderPitch.value = settings.epubTtsPitch.coerceIn(
			binding.sliderPitch.valueFrom,
			binding.sliderPitch.valueTo,
		)
		updateLabels()
		binding.groupVoices.addOnButtonCheckedListener(voiceListener)
		controller.isPlaying.observe(lifecycleOwner) { isPlaying ->
			binding.buttonPlay.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
			binding.buttonPlay.contentDescription = context.getString(
				if (isPlaying) R.string.pause else R.string.resume,
			)
			// The voice list only exists once the engine is up, which is right about now.
			updateVoices()
		}
	}

	/** Offers one dot per voice the engine actually has for this book's language. */
	private fun updateVoices() {
		val presets = tts?.voicePresets().orEmpty()
		val buttons = voiceButtons
		buttons.forEachIndexed { index, button -> button.isGone = index >= presets.size }
		binding.labelVoice.setText(if (presets.isEmpty()) R.string.tts_unavailable else R.string.tts_voice)
		if (presets.isEmpty()) {
			return
		}
		val selected = tts?.selectedVoiceIndex()?.coerceAtMost(presets.lastIndex) ?: 0
		binding.groupVoices.removeOnButtonCheckedListener(voiceListener)
		binding.groupVoices.check(buttons[selected].id)
		binding.groupVoices.addOnButtonCheckedListener(voiceListener)
	}

	private val voiceButtons
		get() = listOf(
			binding.buttonVoice0,
			binding.buttonVoice1,
			binding.buttonVoice2,
			binding.buttonVoice3,
		)

	private val tuningListener = object : Slider.OnSliderTouchListener {
		override fun onStartTrackingTouch(slider: Slider) = Unit

		override fun onStopTrackingTouch(slider: Slider) {
			tts?.applyTuning()
		}
	}

	private val voiceListener = MaterialButtonToggleGroup.OnButtonCheckedListener { _, checkedId, isChecked ->
		if (!isChecked) {
			return@OnButtonCheckedListener
		}
		val index = voiceButtons.indexOfFirst { it.id == checkedId }
		if (index >= 0) {
			tts?.selectVoice(index)
			updateVoices()
		}
	}

	override fun onClick(v: View) {
		val controller = tts ?: return
		when (v.id) {
			R.id.button_close -> hide()
			R.id.button_play -> controller.toggle()
			R.id.button_next -> controller.skip(1)
			R.id.button_previous -> controller.skip(-1)
		}
	}

	override fun onValueChange(slider: Slider, value: Float, fromUser: Boolean) {
		if (fromUser) {
			if (slider.id == R.id.slider_pitch) {
				settings.epubTtsPitch = value
			} else {
				settings.epubTtsSpeed = value
			}
			tts?.applySettings()
		}
		updateLabels()
	}

	override fun setVisibility(visibility: Int) {
		super.setVisibility(visibility)
		onVisibilityChangeListener?.onVisibilityChanged(this, visibility)
	}

	fun show() {
		setupVisibilityTransition()
		isVisible = true
		updateVoices()
	}

	fun hide() {
		setupVisibilityTransition()
		isVisible = false
	}

	fun showOrHide() {
		setupVisibilityTransition()
		isVisible = !isVisible
		if (isVisible) updateVoices()
	}

	private fun setupVisibilityTransition() {
		if (context.isAnimationsEnabled) {
			val sceneRoot = parentView ?: return
			val transition = Slide()
			transition.addTarget(this)
			TransitionManager.beginDelayedTransition(sceneRoot, transition)
		}
	}

	private fun updateLabels() {
		binding.labelSpeed.text = context.getString(R.string.speed) + "  " +
			context.getString(R.string.tts_speed_value, binding.sliderSpeed.value)
		binding.labelPitch.text = context.getString(R.string.tts_pitch) + "  " +
			context.getString(R.string.tts_speed_value, binding.sliderPitch.value)
	}
}
