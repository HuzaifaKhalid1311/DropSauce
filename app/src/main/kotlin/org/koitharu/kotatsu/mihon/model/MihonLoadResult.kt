package org.koitharu.kotatsu.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import org.koitharu.kotatsu.core.model.ContentWarning

sealed class MihonLoadResult {
	data class Success(
		val pkgName: String,
		val appName: String,
		val versionCode: Long,
		val versionName: String,
		val libVersion: Double,
		val lang: String,
		val contentWarning: ContentWarning,
		val sources: List<Source>,
		val isShared: Boolean = true,
	) : MihonLoadResult() {
		val catalogueSources: List<CatalogueSource>
			get() = sources.filterIsInstance<CatalogueSource>()
	}

	data class Error(
		val pkgName: String,
		val message: String,
		val exception: Throwable? = null,
	) : MihonLoadResult()

	data class Untrusted(
		val pkgName: String,
		val appName: String,
		val versionCode: Long,
		val versionName: String,
	) : MihonLoadResult()
}

data class MihonExtensionInfo(
	val pkgName: String,
	val appName: String,
	val versionCode: Long,
	val versionName: String,
	val libVersion: Double,
	val lang: String,
	val contentWarning: ContentWarning,
	val sourceClassName: String,
	val apkPath: String,
	/** SHA-256 of the APK signing certs, matched against a repo's signingKeyFingerprint to attribute it. */
	val signatures: List<String> = emptyList(),
	val isShared: Boolean = true,
)
