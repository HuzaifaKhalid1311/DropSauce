package org.koitharu.kotatsu.settings.sources.catalog

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalExtensionRepoRepositoryTest {

	@Test
	fun `resolveApkUrl resolves relative path against repo url`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveApkUrl(
			repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
			apkName = "tachiyomi-all.ahottie-v1.4.2.apk",
		)
		assertEquals(
			"https://raw.githubusercontent.com/keiyoushi/extensions/repo/tachiyomi-all.ahottie-v1.4.2.apk",
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
}
