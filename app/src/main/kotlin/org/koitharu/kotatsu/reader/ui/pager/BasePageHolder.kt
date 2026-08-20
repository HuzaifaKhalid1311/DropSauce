package org.koitharu.kotatsu.reader.ui.pager

import android.content.ComponentCallbacks2
import android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.view.View
import androidx.annotation.CallSuper
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import com.davemorrissey.labs.subscaleview.DefaultOnImageEventListener
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.ui.list.lifecycle.LifecycleAwareViewHolder
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isLowRamDevice
import org.koitharu.kotatsu.core.util.ext.isSerializable
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.databinding.LayoutPageInfoBinding
import org.koitharu.kotatsu.parsers.util.ifZero
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.UpscaleEffect
import org.koitharu.kotatsu.reader.ui.config.ReaderSettings
import org.koitharu.kotatsu.reader.ui.pager.vm.PageState
import org.koitharu.kotatsu.reader.ui.pager.vm.PageViewModel
import org.koitharu.kotatsu.reader.ui.pager.webtoon.WebtoonHolder

abstract class BasePageHolder<B : ViewBinding>(
	protected val binding: B,
	loader: PageLoader,
	readerSettingsProducer: ReaderSettings.Producer,
	networkState: NetworkState,
	exceptionResolver: ExceptionResolver,
	lifecycleOwner: LifecycleOwner,
) : LifecycleAwareViewHolder(binding.root, lifecycleOwner), DefaultOnImageEventListener, ComponentCallbacks2 {

	protected val viewModel = PageViewModel(
		loader = loader,
		settingsProducer = readerSettingsProducer,
		networkState = networkState,
		exceptionResolver = exceptionResolver,
		isWebtoon = this is WebtoonHolder,
	)
	protected val bindingInfo = LayoutPageInfoBinding.bind(binding.root)
	protected abstract val ssiv: SubsamplingScaleImageView

	protected val settings: ReaderSettings
		get() = viewModel.settingsProducer.value

	val context: Context
		get() = itemView.context

	var boundData: ReaderPage? = null
		private set

	private var isProgressPending = false

	init {
		lifecycleScope.launch(Dispatchers.Main) {
			ssiv.bindToLifecycle(this@BasePageHolder)
			ssiv.isEagerLoadingEnabled = !context.isLowRamDevice()
			ssiv.addOnImageEventListener(viewModel)
			ssiv.addOnImageEventListener(this@BasePageHolder)
		}
		val clickListener = View.OnClickListener { v ->
			when (v.id) {
				R.id.button_retry -> viewModel.retry(
					page = boundData?.toMangaPage() ?: return@OnClickListener,
					isFromUser = true,
				)

				R.id.button_copy -> viewModel.copyErrorToClipboard(v.context)
			}
		}
		bindingInfo.buttonRetry.setOnClickListener(clickListener)
		bindingInfo.buttonCopy.setOnClickListener(clickListener)
	}

	@CallSuper
	protected open fun onConfigChanged(settings: ReaderSettings) {
		settings.applyBackground(itemView)
		if (settings.applyBitmapConfig(ssiv)) {
			reloadImage()
		} else if (viewModel.state.value is PageState.Shown) {
			onReady()
		}
		ssiv.applyDownSampling(isResumed())
		applyUpscale()
	}

	fun reloadImage() {
		val source = (viewModel.state.value as? PageState.Shown)?.source ?: return
		settings.applyBitmapConfig(ssiv)
		ssiv.setImage(source)
	}

	fun bind(data: ReaderPage) {
		if (boundData?.id != data.id) {
			clearUpscale()
		}
		boundData = data
		viewModel.onBind(data.toMangaPage())
		onBind(data)
	}

	@CallSuper
	protected open fun onBind(data: ReaderPage) = Unit

	override fun onCreate() {
		super.onCreate()
		context.registerComponentCallbacks(this)
		viewModel.state.observe(this, ::onStateChanged)
		viewModel.settingsProducer.observe(this, ::onConfigChanged)
	}

	override fun onResume() {
		super.onResume()
		ssiv.applyDownSampling(isForeground = true)
		if (viewModel.state.value is PageState.Error && !viewModel.isLoading()) {
			boundData?.let { viewModel.retry(it.toMangaPage(), isFromUser = false) }
		}
	}

	override fun onPause() {
		super.onPause()
		ssiv.applyDownSampling(isForeground = false)
	}

	override fun onDestroy() {
		context.unregisterComponentCallbacks(this)
		super.onDestroy()
	}

	open fun onAttachedToWindow() = Unit

	open fun onDetachedFromWindow() = Unit

	@CallSuper
	open fun onRecycled() {
		hideProgress()
		clearUpscale()
		viewModel.onRecycle()
		ssiv.recycle()
	}

	override fun onTrimMemory(level: Int) {
		// TODO
	}

	override fun onConfigurationChanged(newConfig: Configuration) = Unit

	@Deprecated("Deprecated in Java")
	@Suppress("DEPRECATION")
	final override fun onLowMemory() = onTrimMemory(TRIM_MEMORY_COMPLETE)

	/**
	 * Decides the progress indicator's mode once per page, a moment after loading starts: real
	 * download progress when the server reported a content length by then, indeterminate otherwise.
	 * The mode is never changed while the indicator is on screen - Material forbids it, and the
	 * hide/re-show it forces looks like the progress rewinding and starting over.
	 */
	private val showProgressRunnable = Runnable {
		isProgressPending = false
		val bar = bindingInfo.progressBar
		val progress = (viewModel.state.value as? PageState.Loading)?.progress ?: -1
		if (bar.visibility != View.VISIBLE) {
			bar.isIndeterminate = progress !in 0..100
		}
		if (!bar.isIndeterminate && progress in 0..100) {
			bar.progress = progress
		}
		bar.show()
	}

	private fun showProgress(progress: Int) {
		val bar = bindingInfo.progressBar
		when {
			isProgressPending -> Unit // mode not decided yet, the runnable picks up the latest value
			bar.visibility != View.VISIBLE -> {
				isProgressPending = true
				bar.postDelayed(showProgressRunnable, PROGRESS_MODE_DELAY)
			}

			!bar.isIndeterminate && progress in 0..100 -> bar.setProgressCompat(progress, true)
		}
	}

	private fun hideProgress() {
		bindingInfo.progressBar.removeCallbacks(showProgressRunnable)
		isProgressPending = false
		bindingInfo.progressBar.hide()
	}

	protected open fun onStateChanged(state: PageState) {
		bindingInfo.layoutError.isVisible = state is PageState.Error
		bindingInfo.layoutProgress.isGone = state.isFinalState()
		when (state) {
			is PageState.Converting -> {
				bindingInfo.textViewStatus.setText(R.string.processing_)
				bindingInfo.textViewStatus.isVisible = true
			}

			is PageState.Empty -> {
				bindingInfo.textViewStatus.isVisible = false
			}

			is PageState.Error -> {
				val e = state.error
				bindingInfo.textViewError.text = e.getDisplayMessage(context.resources)
				bindingInfo.buttonRetry.setText(
					ExceptionResolver.getResolveStringId(e).ifZero { R.string.try_again },
				)
				bindingInfo.buttonCopy.isVisible = e.isSerializable()
				bindingInfo.layoutError.isVisible = true
				hideProgress()
			}

			is PageState.Loaded -> {
				bindingInfo.textViewStatus.setText(R.string.preparing_)
				bindingInfo.textViewStatus.isVisible = true
				settings.applyBitmapConfig(ssiv)
				ssiv.setImage(state.source)
			}

			is PageState.Loading -> {
				bindingInfo.textViewStatus.isVisible = false
				showProgress(state.progress)
				if (state.preview != null && ssiv.getState() == null) {
					settings.applyBitmapConfig(ssiv)
					ssiv.setImage(state.preview)
				}
			}

			is PageState.Shown -> ssiv.post { applyUpscale() }
		}
	}

	private fun applyUpscale() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			return
		}
		// gate on the page's native resolution vs screen, not the current zoom level,
		// so high-res pages never get processed no matter how far the user zooms in
		val fitScale = if (ssiv.isReady && ssiv.sWidth > 0) {
			ssiv.width / ssiv.sWidth.toFloat()
		} else {
			0f
		}
		val isPowerSaveMode = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
		val effect = if (settings.isUpscaleEnabled && !isPowerSaveMode && fitScale > UpscaleEffect.MIN_SCALE) {
			UpscaleEffect.create(fitScale)
		} else {
			null
		}
		boundData?.let { UpscaleEffect.registerView(it.id, ssiv) }
		ssiv.setRenderEffect(effect)
		boundData?.let { UpscaleEffect.setActive(it.id, effect != null) }
	}

	private fun clearUpscale() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
			return
		}
		ssiv.setRenderEffect(null)
		boundData?.let { UpscaleEffect.setActive(it.id, false) }
	}

	private companion object {

		const val PROGRESS_MODE_DELAY = 500L
	}

	protected fun SubsamplingScaleImageView.applyDownSampling(isForeground: Boolean) {
		downSampling = when {
			isForeground || !settings.isReaderOptimizationEnabled -> 1
			BuildConfig.DEBUG -> 32
			context.isLowRamDevice() -> 8
			else -> 4
		}
	}
}
