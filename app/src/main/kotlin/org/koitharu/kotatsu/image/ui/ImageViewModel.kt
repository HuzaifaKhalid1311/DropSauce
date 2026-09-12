package org.koitharu.kotatsu.image.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import java.io.File
import javax.inject.Inject

/**
 * Saving and sharing both work off the bitmap the viewer already decoded, so neither re-runs the
 * whole image pipeline (fetch + decode) just to write the same pixels out again.
 */
@HiltViewModel
class ImageViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
) : BaseViewModel() {

	val onImageSaved = MutableEventFlow<Uri>()

	/** Emits the cache file the image was written to, ready to be handed to a share intent. */
	val onImageReadyToShare = MutableEventFlow<File>()

	fun saveImage(destination: Uri, bitmap: Bitmap) {
		launchLoadingJob(Dispatchers.Default) {
			runInterruptible(Dispatchers.IO) {
				context.contentResolver.openOutputStream(destination)?.use { output ->
					check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
				} ?: error("Cannot open output stream")
			}
			onImageSaved.call(destination)
		}
	}

	fun shareImage(bitmap: Bitmap) {
		launchLoadingJob(Dispatchers.Default) {
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

	private companion object {

		const val SHARE_DIR = "shared_images"
	}
}
