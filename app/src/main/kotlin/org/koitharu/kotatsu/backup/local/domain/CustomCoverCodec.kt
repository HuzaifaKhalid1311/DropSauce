package org.koitharu.kotatsu.backup.local.domain

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.core.net.toUri
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.isFileUri
import org.koitharu.kotatsu.core.util.ext.toFileOrNull
import org.koitharu.kotatsu.core.util.ext.toMimeTypeOrNull
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Turns a locally stored custom cover into portable base64 data and back. A raw file path is
 * useless on another device, so covers travel as bytes and are re-materialized into a new local
 * file keyed by manga id + content digest. Shared by Google Drive sync and local backups.
 */
@Reusable
class CustomCoverCodec @Inject constructor(
	@ApplicationContext private val context: Context,
) {

	class EncodedCover(val data: String, val extension: String?)

	class RawCover(val bytes: ByteArray, val extension: String?)

	suspend fun read(url: String?): EncodedCover? = readRaw(url)?.let {
		EncodedCover(data = Base64.encodeToString(it.bytes, Base64.NO_WRAP), extension = it.extension)
	}

	/** Bytes of a locally stored custom cover, or null when [url] isn't a local file/content uri. */
	suspend fun readRaw(url: String?): RawCover? {
		val uri = url?.toUriOrNull() ?: return null
		if (!uri.isFileUri() && uri.scheme != "content") {
			return null
		}
		return withContext(Dispatchers.IO) {
			runCatching {
				val bytes = if (uri.isFileUri()) {
					uri.toFileOrNull()?.takeIf(File::isFile)?.readBytes()
				} else {
					context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
				} ?: return@runCatching null
				val extension = uri.lastPathSegment
					?.substringAfterLast('.', "")
					?.takeIf { it.isSafeFileExtension() }
					?: context.contentResolver.getType(uri)
						?.toMimeTypeOrNull()
						?.let(MimeTypes::getExtension)
				RawCover(bytes, extension)
			}.onFailure {
				Log.w(TAG, "failed to read custom cover '$url'", it)
			}.getOrNull()
		}
	}

	suspend fun materialize(
		mangaId: Long,
		coverData: String,
		coverFileExtension: String?,
		previousUrl: String?,
	): String? = runCatching { Base64.decode(coverData, Base64.DEFAULT) }
		.getOrNull()
		?.let { materialize(mangaId, it, coverFileExtension, previousUrl) }

	/** Writes [bytes] as this device's copy of a custom cover and returns its uri. */
	suspend fun materialize(
		mangaId: Long,
		bytes: ByteArray,
		coverFileExtension: String?,
		previousUrl: String?,
	): String? = withContext(Dispatchers.IO) {
		runCatching {
			val directory = context.getExternalFilesDir(COVERS_DIR) ?: return@runCatching null
			if (!directory.exists() && !directory.mkdirs()) {
				return@runCatching null
			}
			val digest = sha256(bytes).take(24)
			val extension = coverFileExtension
				?.takeIf { it.isSafeFileExtension() }
				?.let { ".$it" }
				.orEmpty()
			val destination = File(directory, "$SYNCED_COVER_PREFIX${mangaId}_$digest$extension")
			if (!destination.isFile || !destination.readBytes().contentEquals(bytes)) {
				destination.writeBytes(bytes)
			}
			deleteReplacedSyncedCover(previousUrl, destination)
			destination.toUri().toString()
		}.onFailure {
			Log.w(TAG, "failed to restore custom cover for manga $mangaId", it)
		}.getOrNull()
	}

	fun isPortableCoverUrl(url: String?): Boolean {
		val uri = url?.toUriOrNull() ?: return true
		return !uri.isFileUri() && uri.scheme != "content"
	}

	private fun deleteReplacedSyncedCover(previousUrl: String?, replacement: File) {
		val previous = previousUrl?.toUriOrNull()?.toFileOrNull() ?: return
		val coverDirectory = replacement.parentFile?.canonicalFile ?: return
		val oldFile = runCatching { previous.canonicalFile }.getOrNull() ?: return
		if (oldFile != replacement.canonicalFile &&
			oldFile.parentFile == coverDirectory &&
			oldFile.name.startsWith(SYNCED_COVER_PREFIX)
		) {
			oldFile.delete()
		}
	}

	private fun String.isSafeFileExtension(): Boolean =
		length in 1..10 && all { it.isLetterOrDigit() }

	companion object {

		/** Hex SHA-256 — the content address of a cover in the sync store. */
		fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
			.digest(bytes)
			.joinToString("") { "%02x".format(it) }

		private const val TAG = "CustomCoverCodec"
		private const val COVERS_DIR = "covers"
		private const val SYNCED_COVER_PREFIX = "sync_"
	}
}
