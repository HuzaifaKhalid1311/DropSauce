package org.koitharu.kotatsu.image.ui

import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.lifecycle
import coil3.target.GenericViewTarget
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.exceptions.resolve.SnackbarErrorObserver
import org.koitharu.kotatsu.core.image.CoilMemoryCacheKey
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.util.ShareHelper
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.core.util.ext.consumeAll
import org.koitharu.kotatsu.core.util.ext.end
import org.koitharu.kotatsu.core.util.ext.enqueueWith
import org.koitharu.kotatsu.core.util.ext.getDisplayIcon
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.start
import org.koitharu.kotatsu.databinding.ActivityImageBinding
import org.koitharu.kotatsu.databinding.ItemErrorStateBinding
import javax.inject.Inject

@AndroidEntryPoint
class ImageActivity : BaseActivity<ActivityImageBinding>(),
	ImageRequest.Listener,
	View.OnClickListener {

	@Inject
	lateinit var coil: ImageLoader

	private var errorBinding: ItemErrorStateBinding? = null
	private val viewModel: ImageViewModel by viewModels()
	private lateinit var imageMenuProvider: ImageMenuProvider
	private var manga: Manga? = null

	/** The decoded image currently on screen; save and share both write these exact pixels out. */
	private var loadedBitmap: Bitmap? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityImageBinding.inflate(layoutInflater))
		viewBinding.buttonBack.setOnClickListener(this)
		viewBinding.buttonSave.setOnClickListener(this)
		viewBinding.buttonShare.setOnClickListener(this)
		viewBinding.buttonEdit.setOnClickListener(this)

		imageMenuProvider = ImageMenuProvider(
			activity = this,
			snackbarHost = viewBinding.root,
			viewModel = viewModel,
			bitmapProvider = { loadedBitmap },
		)
		manga = intent.getParcelableExtraCompat<ParcelableManga>(AppRouter.KEY_MANGA)?.manga
		viewBinding.buttonEdit.isVisible = manga != null
		viewModel.isLoading.observe(this, ::onLoadingStateChanged)
		viewModel.onError.observeEvent(this, SnackbarErrorObserver(viewBinding.root, null))
		viewModel.onImageSaved.observeEvent(this, ::onImageSaved)
		viewModel.onImageReadyToShare.observeEvent(this) { ShareHelper(this).shareImage(it) }
		loadImage()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_back -> dispatchNavigateUp()
			R.id.button_save -> imageMenuProvider.requestSave()
			R.id.button_share -> loadedBitmap?.let(viewModel::shareImage)
			R.id.button_edit -> manga?.let { router.openMangaOverrideConfig(it) }
			else -> loadImage()
		}
	}

	override fun onError(request: ImageRequest, result: ErrorResult) {
		viewBinding.progressBar.hide()
		with(errorBinding ?: ItemErrorStateBinding.bind(viewBinding.stubError.inflate())) {
			errorBinding = this
			root.isVisible = true
			textViewError.text = result.throwable.getDisplayMessage(resources)
			textViewError.setCompoundDrawablesWithIntrinsicBounds(0, result.throwable.getDisplayIcon(), 0, 0)
			buttonRetry.isVisible = true
			buttonRetry.setOnClickListener(this@ImageActivity)
		}
	}

	override fun onStart(request: ImageRequest) {
		viewBinding.progressBar.show()
		(errorBinding?.root ?: viewBinding.stubError).isVisible = false
	}

	override fun onSuccess(request: ImageRequest, result: SuccessResult) {
		viewBinding.progressBar.hide()
		(errorBinding?.root ?: viewBinding.stubError).isVisible = false
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		val barsInsets = insets.getInsets(typeMask)
		val baseMargin = v.resources.getDimensionPixelOffset(R.dimen.screen_padding)
		viewBinding.layoutActions.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			marginStart = barsInsets.start(v) + baseMargin
			marginEnd = barsInsets.end(v) + baseMargin
			bottomMargin = barsInsets.bottom + baseMargin
		}
		viewBinding.buttonBack.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			marginStart = barsInsets.start(v) + baseMargin
			topMargin = barsInsets.top + baseMargin
		}
		return insets.consumeAll(typeMask)
	}

	private fun loadImage() {
		ImageRequest.Builder(this)
			.data(intent.data)
			.memoryCacheKey(intent.getParcelableExtraCompat<CoilMemoryCacheKey>(AppRouter.KEY_PREVIEW)?.data)
			.memoryCachePolicy(CachePolicy.READ_ONLY)
			.lifecycle(this)
			.listener(this)
			.mangaSourceExtra(MangaSource(intent.getStringExtra(AppRouter.KEY_SOURCE)))
			.target(
				SsivTarget(viewBinding.ssiv) { bitmap ->
					loadedBitmap = bitmap
					updateActionsEnabled()
				},
			)
			.enqueueWith(coil)
	}

	private fun onImageSaved(uri: Uri) {
		Snackbar.make(viewBinding.root, R.string.page_saved, Snackbar.LENGTH_LONG)
			.setAction(R.string.share) {
				ShareHelper(this).shareImage(uri)
			}.show()
	}

	private fun onLoadingStateChanged(isLoading: Boolean) = updateActionsEnabled(isLoading)

	private fun updateActionsEnabled(isLoading: Boolean = viewModel.isLoading.value) {
		val isEnabled = !isLoading && loadedBitmap != null
		viewBinding.buttonSave.isEnabled = isEnabled
		viewBinding.buttonShare.isEnabled = isEnabled
	}

	private class SsivTarget(
		override val view: SubsamplingScaleImageView,
		private val onBitmapChanged: (Bitmap?) -> Unit,
	) : GenericViewTarget<SubsamplingScaleImageView>() {

		override var drawable: Drawable? = null
			set(value) {
				field = value
				setImageDrawable(value)
			}

		override fun equals(other: Any?): Boolean {
			return (this === other) || (other is SsivTarget && view == other.view)
		}

		override fun hashCode() = view.hashCode()

		override fun toString() = "SsivTarget(view=$view)"

		private fun setImageDrawable(drawable: Drawable?) {
			if (drawable != null) {
				val bitmap = drawable.toBitmap()
				view.setImage(ImageSource.bitmap(bitmap))
				onBitmapChanged(bitmap)
			} else {
				view.recycle()
				onBitmapChanged(null)
			}
		}
	}
}
