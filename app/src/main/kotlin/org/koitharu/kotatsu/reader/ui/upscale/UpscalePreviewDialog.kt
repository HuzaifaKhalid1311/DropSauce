package org.koitharu.kotatsu.reader.ui.upscale

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.ComposeAlertDialogFragment
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.isZipUri
import org.koitharu.kotatsu.databinding.DialogUpscalePreviewBinding
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.reader.domain.PageLoader
import org.koitharu.kotatsu.reader.domain.UpscaleEffect
import org.koitharu.kotatsu.reader.ui.ReaderViewModel
import org.koitharu.kotatsu.settings.compose.SettingsSearchHighlight
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@AndroidEntryPoint
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class UpscalePreviewDialog : ComposeAlertDialogFragment() {

	private val viewModel by activityViewModels<ReaderViewModel>()

	@Inject
	lateinit var pageLoader: PageLoader

	private var binding: DialogUpscalePreviewBinding? = null

	private var clipFraction = 0.5f
	private var bitmap: Bitmap? = null
	private val imageMatrix = Matrix()
	private var isDraggingDivider = false
	private var lastTouchX = 0f
	private var lastTouchY = 0f
	private lateinit var scaleDetector: ScaleGestureDetector

	@Composable
	override fun Content() {
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_sparkles),
			title = stringResource(R.string.upscale_preview),
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(R.string.close),
				onClick = ::dismiss,
			),
		) {
			AndroidView(
				factory = ::createPreviewView,
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}

	@SuppressLint("ClickableViewAccessibility")
	private fun createPreviewView(context: Context): View {
		val b = DialogUpscalePreviewBinding.inflate(LayoutInflater.from(context))
		binding = b
		val cornerRadius = resources.getDimension(R.dimen.margin_normal)
		b.imagesContainer.outlineProvider = object : ViewOutlineProvider() {
			override fun getOutline(view: View, outline: Outline) {
				outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
			}
		}
		b.imagesContainer.clipToOutline = true
		scaleDetector = ScaleGestureDetector(
			context,
			object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
				override fun onScale(detector: ScaleGestureDetector): Boolean {
					imageMatrix.postScale(
						detector.scaleFactor, detector.scaleFactor,
						detector.focusX, detector.focusY,
					)
					applyMatrix()
					return true
				}
			},
		)
		b.imagesContainer.setOnTouchListener { v, event -> onImageTouch(v, event) }
		b.textSettings.setOnClickListener {
			SettingsSearchHighlight.request(getString(R.string.reader_upscale))
			startActivity(AppRouter.readerSettingsIntent(it.context))
			dismiss()
		}
		loadPreview()
		return b.root
	}

	override fun onDestroyView() {
		binding = null
		super.onDestroyView()
	}

	private fun onImageTouch(v: View, event: MotionEvent): Boolean {
		val b = binding ?: return false
		scaleDetector.onTouchEvent(event)
		when (event.actionMasked) {
			MotionEvent.ACTION_DOWN -> {
				// The popup body scrolls; keep it from stealing pan/pinch/divider drags.
				v.parent.requestDisallowInterceptTouchEvent(true)
				isDraggingDivider = b.divider.isVisible &&
					abs(event.x - b.divider.translationX) < v.width * 0.08f
				lastTouchX = event.x
				lastTouchY = event.y
			}

			MotionEvent.ACTION_POINTER_DOWN -> isDraggingDivider = false

			MotionEvent.ACTION_MOVE -> if (isDraggingDivider) {
				applyClip((event.x / v.width).coerceIn(0f, 1f))
			} else if (event.pointerCount == 1 && !scaleDetector.isInProgress) {
				imageMatrix.postTranslate(event.x - lastTouchX, event.y - lastTouchY)
				applyMatrix()
				lastTouchX = event.x
				lastTouchY = event.y
			} else {
				lastTouchX = event.x
				lastTouchY = event.y
			}

			MotionEvent.ACTION_UP -> {
				if (event.eventTime - event.downTime < 200) {
					v.performClick()
				}
				isDraggingDivider = false
			}
		}
		return true
	}

	private fun loadPreview() = lifecycleScope.launch {
		val b = binding ?: return@launch
		runCatchingCancellable {
			val page = checkNotNull(viewModel.getCurrentPage()) { "Cannot find current page" }
			val uri = pageLoader.loadPage(page, force = false)
			page.id to runInterruptible(Dispatchers.Default) { decode(uri) }
		}.onSuccess { (pageId, bmp) ->
			bitmap = bmp
			b.imagesContainer.doOnLayout { showBitmap(bmp, pageId) }
		}.onFailure { e ->
			b.progressBar.isVisible = false
			b.textStatus.text = e.getDisplayMessage(resources)
			b.textStatus.isVisible = true
		}
	}

	private fun showBitmap(bitmap: Bitmap, pageId: Long) {
		val b = binding ?: return
		b.progressBar.isVisible = false
		val fitScale = resources.displayMetrics.widthPixels / bitmap.width.toFloat()
		// start from what the reader is showing right now; fall back to fit-width
		val viewport = UpscaleEffect.viewportFor(pageId)
		val scale = viewport?.scale ?: fitScale
		val cx = viewport?.centerX ?: (bitmap.width / 2f)
		val cy = viewport?.centerY ?: (b.imagesContainer.height / (2f * scale))
		imageMatrix.setScale(scale, scale)
		imageMatrix.postTranslate(
			b.imagesContainer.width / 2f - cx * scale,
			b.imagesContainer.height / 2f - cy * scale,
		)
		b.imageOriginal.setImageBitmap(bitmap)
		b.imageEnhanced.setImageBitmap(bitmap)
		applyMatrix()
		if (fitScale > UpscaleEffect.MIN_SCALE) {
			b.imageEnhanced.setRenderEffect(UpscaleEffect.create(fitScale))
		} else {
			b.textStatus.setText(R.string.upscale_not_applied)
			b.textStatus.isVisible = true
			b.divider.isVisible = false
			b.dividerThumb.isVisible = false
		}
		applyClip(clipFraction)
	}

	private fun applyMatrix() {
		val b = binding ?: return
		val bmp = bitmap ?: return
		clampMatrix(bmp, b.imagesContainer.width.toFloat(), b.imagesContainer.height.toFloat())
		b.imageOriginal.imageMatrix = imageMatrix
		b.imageEnhanced.imageMatrix = imageMatrix
	}

	private fun clampMatrix(bmp: Bitmap, viewW: Float, viewH: Float) {
		val values = FloatArray(9)
		imageMatrix.getValues(values)
		var scale = values[Matrix.MSCALE_X]
		val fitScale = min(viewW / bmp.width, viewH / bmp.height)
		val clamped = scale.coerceIn(fitScale * 0.5f, max(fitScale, 1f) * 10f)
		if (clamped != scale) {
			val f = clamped / scale
			imageMatrix.postScale(f, f, viewW / 2f, viewH / 2f)
			imageMatrix.getValues(values)
			scale = clamped
		}
		val imgW = bmp.width * scale
		val imgH = bmp.height * scale
		values[Matrix.MTRANS_X] = clampTranslation(values[Matrix.MTRANS_X], imgW, viewW)
		values[Matrix.MTRANS_Y] = clampTranslation(values[Matrix.MTRANS_Y], imgH, viewH)
		imageMatrix.setValues(values)
	}

	private fun clampTranslation(t: Float, imgSize: Float, viewSize: Float) = if (imgSize <= viewSize) {
		(viewSize - imgSize) / 2f
	} else {
		t.coerceIn(viewSize - imgSize, 0f)
	}

	private fun applyClip(value: Float) {
		val b = binding ?: return
		val width = b.imagesContainer.width
		if (width == 0) {
			return
		}
		clipFraction = value
		val cut = (width * value).toInt()
		b.imageEnhanced.clipBounds = Rect(cut, 0, width, b.imagesContainer.height)
		b.divider.translationX = cut.toFloat()
		b.dividerThumb.translationX = cut - b.dividerThumb.width / 2f
	}

	private fun decode(uri: Uri): Bitmap = if (uri.isZipUri()) {
		ZipFile(uri.schemeSpecificPart).use { zip ->
			val entry = zip.getEntry(uri.fragment)
			zip.getInputStream(entry).use {
				BitmapDecoderCompat.decode(it, MimeTypes.getMimeTypeFromExtension(entry.name))
			}
		}
	} else {
		BitmapDecoderCompat.decode(File(requireNotNull(uri.path) { "Invalid page uri" }))
	}

	companion object {

		private const val TAG = "UpscalePreviewDialog"

		fun show(fm: FragmentManager) {
			if (UpscaleEffect.isSupported) {
				UpscalePreviewDialog().show(fm, TAG)
			}
		}
	}
}
