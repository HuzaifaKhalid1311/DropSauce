package org.koitharu.kotatsu.core.util

import android.content.Context
import android.net.Uri
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.model.Manga
import java.io.File

private const val TYPE_TEXT = "text/plain"
private const val TYPE_IMAGE = "image/*"
private const val TYPE_CBZ = "application/x-cbz"

class ShareHelper(private val context: Context) {

	fun shareMangaLink(manga: Manga) {
		val text = "${manga.title}\n \n${manga.publicUrl}"
		ShareCompat.IntentBuilder(context)
			.setText(text)
			.setType(TYPE_TEXT)
			.setChooserTitle(context.getString(R.string.share_s, manga.title))
			.startChooser()
	}

	fun shareMangaLinks(manga: Collection<Manga>) {
		if (manga.isEmpty()) {
			return
		}
		if (manga.size == 1) {
			shareMangaLink(manga.first())
			return
		}
		val text = manga.joinToString("\n \n") {
			"${it.title} - ${it.publicUrl}"
		}
		ShareCompat.IntentBuilder(context)
			.setText(text)
			.setType(TYPE_TEXT)
			.setChooserTitle(R.string.share)
			.startChooser()
	}

	fun shareCbz(files: Collection<File>) {
		if (files.isEmpty()) {
			return
		}
		val intentBuilder = ShareCompat.IntentBuilder(context)
			.setType(TYPE_CBZ)
		for (file in files) {
			val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
			intentBuilder.addStream(uri)
		}
		files.singleOrNull()?.let {
			intentBuilder.setChooserTitle(context.getString(R.string.share_s, it.name))
		} ?: run {
			intentBuilder.setChooserTitle(R.string.share)
		}
		intentBuilder.startChooser()
	}

	/**
	 * The type is resolved from the file name rather than left to the resolver: FileProvider answers
	 * `application/octet-stream` for anything MimeTypeMap does not know (avif, jxl, ...), and a
	 * receiving app handed an octet-stream treats it as a generic file instead of a full-size image.
	 */
	fun shareImage(file: File) {
		val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", file)
		val type = MimeTypes.getMimeTypeFromExtension(file.name)?.toString()
			?: context.contentResolver.getType(uri)
			?: TYPE_IMAGE
		shareImage(uri, type)
	}

	fun shareImage(uri: Uri) = shareImage(
		uri = uri,
		type = context.contentResolver.getType(uri) ?: TYPE_IMAGE,
	)

	private fun shareImage(uri: Uri, type: String) {
		ShareCompat.IntentBuilder(context)
			.setStream(uri)
			.setType(type)
			.setChooserTitle(R.string.share_image)
			.startChooser()
	}

}
