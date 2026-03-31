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
		get() = "MIHON_${catalogueSource.id}:$displayName"

	val displayName: String
		get() = if (hasLanguageSuffix) {
			"${catalogueSource.name} (${getExternalExtensionLanguageDisplayName(language)})"
		} else {
			catalogueSource.name
		}

	val language: String
		get() = catalogueSource.lang

	val sourceId: Long
		get() = catalogueSource.id

	val supportsLatest: Boolean
		get() = catalogueSource.supportsLatest

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is MangaSource) return false
		// Compare by name to support comparison with anonymous MangaSource objects
		// that are created when loading from the database
		return name == other.name
	}

	override fun hashCode(): Int {
		// Use name for hashCode to be consistent with equals
		return name.hashCode()
	}

	override fun toString(): String {
		return "MihonMangaSource(id=${catalogueSource.id}, name=${catalogueSource.name}, lang=$language)"
	}
}
