package org.koitharu.kotatsu.image.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import java.io.File
import javax.inject.Inject

/**
 * Saving and sharing both work off the bitmap the viewer already decoded, so neither re-runs the
 * whole image pipeline (fetch + decode) just to write the same pixels out again.
 */
@HiltViewModel
class ImageViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	savedStateHandle: SavedStateHandle,
	dataRepository: MangaDataRepository,
	settings: AppSettings,
) : BaseViewModel() {

	private val manga = savedStateHandle.get<ParcelableManga>(AppRouter.KEY_MANGA)?.manga

	/** The cover's new url whenever it is edited while open; the same choice the details screen makes. */
	val coverUrl: Flow<String> = if (manga == null) emptyFlow() else {
		dataRepository.observeOverridesTrigger(emitInitialState = false).mapLatest {
			dataRepository.getOverride(manga.id)?.coverUrl.ifNullOrEmpty {
				dataRepository.findMangaById(manga.id, false)?.let { m ->
					(if (settings.isBackdropEnabled) null else m.largeCoverUrl).ifNullOrEmpty { m.coverUrl }
				}
			}.orEmpty()
		}.filter { it.isNotEmpty() }.distinctUntilChanged()
	}

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
