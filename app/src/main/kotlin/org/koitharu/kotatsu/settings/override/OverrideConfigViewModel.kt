package org.koitharu.kotatsu.settings.override

import android.content.Context
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.isNetworkUri
import org.koitharu.kotatsu.core.util.ext.openSource
import org.koitharu.kotatsu.core.util.ext.require
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.md5
import java.io.File
import javax.inject.Inject

private const val DIR_COVERS = "covers"

@HiltViewModel
class OverrideConfigViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	@ApplicationContext private val context: Context,
	private val dataRepository: MangaDataRepository,
) : BaseViewModel() {

	private val manga = savedStateHandle.require<ParcelableManga>(AppRouter.KEY_MANGA).manga

	val data = MutableStateFlow<Pair<Manga, MangaOverride>?>(null)
	val onSaved = MutableEventFlow<Unit>()

	init {
		launchLoadingJob(Dispatchers.Default) {
			// Always use the pristine DB-stored manga so that the editor shows the true
			// source title/cover, regardless of whether the caller already had an override applied.
			val sourceManga = dataRepository.findMangaById(manga.id, false) ?: manga
			data.value = sourceManga to (dataRepository.getOverride(manga.id) ?: emptyOverride())
		}
	}

	fun save(title: String?, description: String?) {
		launchLoadingJob(Dispatchers.Default) {
			val (sourceManga, draftOverride) = checkNotNull(data.value)
			val previousCover = dataRepository.getOverride(sourceManga.id)?.coverUrl
			val override = draftOverride.let {
				it.copy(
					title = title,
					description = description,
					coverUrl = it.coverUrl?.cachedFile(),
				)
			}
			val savedOverride = dataRepository.setOverride(sourceManga, override)
			deleteStaleCachedCover(previousCover, savedOverride?.coverUrl)
			onSaved.call(Unit)
		}
	}

	fun updateCover(coverUri: String?) {
		val snapshot = data.value ?: return
		data.value = snapshot.first to snapshot.second.copy(
			coverUrl = coverUri,
		)
	}

	private suspend fun String.cachedFile(): String {
		val uri = toUriOrNull()
		if (uri == null || uri.isFileUri() || uri.isNetworkUri()) {
			// Remote (http/https) covers are kept as-is so Discord and lists can load them directly.
			return this
		}
		val cacheDir = context.getExternalFilesDir(DIR_COVERS) ?: return this
		val cr = context.contentResolver
		val ext = cr.getType(uri)?.toMimeTypeOrNull()?.let {
			MimeTypes.getExtension(it)
		}
		val fileName = buildString {
			append(this@cachedFile.md5())
			if (!ext.isNullOrEmpty()) {
				append('.')
				append(ext)
			}
		}
		return withContext(Dispatchers.IO) {
			val dest = File(cacheDir, fileName)
			cr.openSource(uri).use { source ->
				dest.sink().buffer().use { sink ->
					sink.writeAll(source)
				}
			}
			dest
		}.toUri().toString()
	}

	private suspend fun deleteStaleCachedCover(oldCover: String?, newCover: String?) {
		if (oldCover.isNullOrEmpty() || oldCover == newCover) {
			return
		}
		withContext(Dispatchers.IO) {
			runCatching {
				val cacheDir = context.getExternalFilesDir(DIR_COVERS)?.canonicalFile ?: return@runCatching
				val file = oldCover.toUriOrNull()?.toFileOrNull()?.canonicalFile ?: return@runCatching
				if (file.parentFile?.canonicalPath == cacheDir.canonicalPath) {
					file.delete()
				}
			}
		}
	}

	private fun emptyOverride() = MangaOverride(null, null, null, null)
}
