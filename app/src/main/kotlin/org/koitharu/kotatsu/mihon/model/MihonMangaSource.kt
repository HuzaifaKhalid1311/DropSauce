package org.koitharu.kotatsu.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import org.koitharu.kotatsu.extensions.runtime.getExternalExtensionLanguageAutonym
import org.koitharu.kotatsu.parsers.model.MangaSource

/** Every novel-extension package is named `…novelextension.<lang>.<site>`, whichever repo built it. */
private const val NOVEL_PACKAGE_MARKER = "novelextension."

data class MihonMangaSource(
	val catalogueSource: CatalogueSource,
	val pkgName: String,
	val isNsfw: Boolean = false,
	/** True when this source has sibling language variants in the same package. */
	val hasLanguageSuffix: Boolean = false,
) : MangaSource {
	override val name: String
		get() = "MIHON_${catalogueSource.id}"

	/**
	 * The source's display name WITHOUT any language suffix. Multiple languages of the same
	 * extension collapse into a single Explore entity, so the language is surfaced separately
	 * (browse top-bar subheading + source settings) rather than appended to the name.
	 */
	val displayName: String
		get() = catalogueSource.name

	/** The active language in its own native form, e.g. "Français", "日本語". */
	val languageDisplayName: String
		get() = getExternalExtensionLanguageAutonym(language)

	val language: String
		get() = catalogueSource.lang

	val sourceId: Long
		get() = catalogueSource.id

	val supportsLatest: Boolean
		get() = catalogueSource.supportsLatest

	/**
	 * True for a Tsundoku novel extension. Read from the source itself rather than from the APK's
	 * manifest flag: a package may ship several sources, and it is the source that knows whether its
	 * chapters are prose. The package name is a fallback for a source that ships in a novel package
	 * but forgets to declare the flag, which would otherwise land it in the image reader.
	 */
	val isNovel: Boolean
		get() = catalogueSource.isNovelSource || pkgName.contains(NOVEL_PACKAGE_MARKER)

	override fun equals(other: Any?): Boolean {
		if (this === other) return true
		if (other !is MangaSource) return false
		val raw = other.name.removePrefix("MIHON_").substringBefore(':')
		return raw.toLongOrNull() == sourceId
	}

	override fun hashCode(): Int {
		return sourceId.hashCode()
	}

	override fun toString(): String {
		return "MihonMangaSource(id=${catalogueSource.id}, name=${catalogueSource.name}, lang=$language)"
	}
}
