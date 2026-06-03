package org.koitharu.kotatsu.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageDisplayName
import org.koitharu.kotatsu.parsers.model.MangaSource

data class MihonMangaSource(
	val catalogueSource: CatalogueSource,
	val pkgName: String,
	val isNsfw: Boolean = false,
	val hasLanguageSuffix: Boolean = false,
) : MangaSource {
	override val name: String
		get() = "MIHON_${catalogueSource.id}:$language"

	val displayName: String
		get() = if (hasLanguageSuffix) {
			"$displayNameWithoutLanguage ($languageDisplayName)"
		} else {
			displayNameWithoutLanguage
		}

	val displayNameWithoutLanguage: String
		get() = catalogueSource.name

	val languageDisplayName: String
		get() = getExternalExtensionLanguageDisplayName(language)

	val language: String
		get() = catalogueSource.lang

	val sourceId: Long
		get() = catalogueSource.id

	val supportsLatest: Boolean
		get() = catalogueSource.supportsLatest

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		return when (other) {
			is MihonMangaSource -> sourceId == other.sourceId && language == other.language
			is MangaSource -> {
				val raw = other.name.removePrefix("MIHON_")
				val otherId = raw.substringBefore(':').toLongOrNull() ?: return false
				val otherLanguage = raw.substringAfter(':', missingDelimiterValue = "")
				sourceId == otherId && (otherLanguage.isEmpty() || language == otherLanguage)
			}
			else -> false
		}
	}

	override fun hashCode(): Int {
		var result = sourceId.hashCode()
		result = 31 * result + language.hashCode()
		return result
	}

	override fun toString(): String {
		return "MihonMangaSource(id=${catalogueSource.id}, name=${catalogueSource.name}, lang=$language)"
	}
}
