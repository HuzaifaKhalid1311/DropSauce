package org.haziffe.dropsauce.mihon.model

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source

sealed class MihonLoadResult {
	data class Success(
		val pkgName: String,
		val appName: String,
		val versionCode: Long,
		val versionName: String,
		val libVersion: Double,
		val lang: String,
		val isNsfw: Boolean,
		val sources: List<Source>,
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
	val isNsfw: Boolean,
	val sourceClassName: String,
	val apkPath: String,
)
