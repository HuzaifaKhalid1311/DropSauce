package org.koitharu.kotatsu.image.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.SavedStateHandle
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.getDrawableOrThrow
import org.koitharu.kotatsu.core.util.ext.mangaSourceExtra
import org.koitharu.kotatsu.core.util.ext.require
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ImageViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val savedStateHandle: SavedStateHandle,
	private val coil: ImageLoader,
) : BaseViewModel() {

	val onImageSaved = MutableEventFlow<Uri>()

	/** Emits the cache file the image was written to, ready to be handed to a share intent. */
	val onImageReadyToShare = MutableEventFlow<File>()

	fun saveImage(destination: Uri) {
		launchLoadingJob(Dispatchers.Default) {
			val bitmap = loadBitmap()
			runInterruptible(Dispatchers.IO) {
				context.contentResolver.openOutputStream(destination)?.use { output ->
					check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
				} ?: error("Cannot open output stream")
			}
			onImageSaved.call(destination)
		}
	}

	fun shareImage() {
		launchLoadingJob(Dispatchers.Default) {
			val bitmap = loadBitmap()
			val file = File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
				.resolve("image_${System.currentTimeMillis()}.png")
			runInterruptible(Dispatchers.IO) {
				file.outputStream().use { output ->
					check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
				}
			}
			onImageReadyToShare.call(file)
		}
	}

	private suspend fun loadBitmap(): Bitmap {
		val request = ImageRequest.Builder(context)
			.data(savedStateHandle.require<Uri>(AppRouter.KEY_DATA))
			.memoryCachePolicy(CachePolicy.DISABLED)
			.mangaSourceExtra(MangaSource(savedStateHandle[AppRouter.KEY_SOURCE]))
			.build()
		return coil.execute(request).getDrawableOrThrow().toBitmap()
	}

	private companion object {

		const val SHARE_DIR = "shared_images"
	}
}
