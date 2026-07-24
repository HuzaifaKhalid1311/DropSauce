package org.koitharu.kotatsu.core.parser

import android.net.Uri
import dagger.Reusable
import org.koitharu.kotatsu.core.util.ext.isHttpUrl
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.Manga
import javax.inject.Inject

@Reusable
class MangaLinkResolver @Inject constructor(
	private val dataRepository: MangaDataRepository,
) {

	suspend fun resolve(uri: Uri): Manga {
		return dataRepository.findMangaByPublicUrl(uri.toString())
			?: throw NotFoundException("Cannot resolve link", uri.toString())
	}

	companion object {

		fun isValidLink(str: String): Boolean {
			return str.isHttpUrl()
		}
	}
}
