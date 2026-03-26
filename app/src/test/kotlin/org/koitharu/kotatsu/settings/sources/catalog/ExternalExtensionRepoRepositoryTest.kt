package org.koitharu.kotatsu.settings.sources.catalog

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalExtensionRepoRepositoryTest {

	@Test
	fun `resolveApkUrl places apk in apk subdirectory relative to repo base`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveApkUrl(
			repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
			apkName = "tachiyomi-all.ahottie-v1.4.2.apk",
		)
		assertEquals(
			"https://raw.githubusercontent.com/keiyoushi/extensions/repo/apk/tachiyomi-all.ahottie-v1.4.2.apk",
			resolved,
		)
	}

	@Test
	fun `resolveApkUrl works with base url without index json`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveApkUrl(
			repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
			apkName = "tachiyomi-all.ahottie-v1.4.2.apk",
		)
		assertEquals(
			"https://raw.githubusercontent.com/keiyoushi/extensions/repo/apk/tachiyomi-all.ahottie-v1.4.2.apk",
			resolved,
		)
	}

	@Test
	fun `resolveApkUrl keeps absolute apk urls unchanged`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveApkUrl(
			repoUrl = "https://example.com/index.min.json",
			apkName = "https://cdn.example.com/ext.apk",
		)
		assertEquals("https://cdn.example.com/ext.apk", resolved)
	}

	@Test
	fun `resolveIconUrl constructs icon url from package name`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveIconUrl(
			repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
			packageName = "eu.kanade.tachiyomi.extension.all.weebdex",
		)
		assertEquals(
			"https://raw.githubusercontent.com/keiyoushi/extensions/repo/icon/eu.kanade.tachiyomi.extension.all.weebdex.png",
			resolved,
		)
	}
}
