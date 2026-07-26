package org.koitharu.kotatsu.settings.sources.catalog

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.mihon.model.MihonExtensionInfo
import org.koitharu.kotatsu.mihon.model.MihonLoadResult

class ExternalExtensionRepoRepositoryTest {

	@Test
	fun `validation reads original store metadata and catalog`() {
		val client = clientReturning { path ->
			when {
				path.endsWith("/repo.json") ->
					"""{"meta":{"name":"Original Store","shortName":"OS","signingKeyFingerprint":"abc123"}}"""
				else ->
					"""[{"name":"MangaFire","pkg":"example.mangafire","apk":"mangafire.apk","code":10,"version":"1.4.10"}]"""
			}
		}

		val result = kotlinx.coroutines.runBlocking {
			ExternalExtensionRepoRepository(client).validateStore("https://example.com/extensions")
		}

		assertEquals("Original Store", result.store.name)
		assertEquals("OS", result.store.shortName)
		assertEquals("abc123", result.store.fingerprint)
		assertEquals("example.mangafire", result.catalog.single().packageName)
	}

	@Test
	fun `validation gives metadata free legacy store a neutral url label`() {
		val client = clientReturning { path ->
			if (path.endsWith("/repo.json")) null else "[]"
		}

		val result = kotlinx.coroutines.runBlocking {
			ExternalExtensionRepoRepository(client).validateStore("https://example.com/community/extensions/")
		}

		assertEquals("example.com/community/extensions", result.store.name)
		assertEquals(null, result.store.fingerprint)
		assertTrue(result.catalog.isEmpty())
	}

	@Test
	fun `validation reads metadata from a new format index`() {
		val client = clientReturning {
			"""
				{
				  "name":"Community Store",
				  "badgeLabel":"CS",
				  "signingKey":"feed1234",
				  "extensionList":{"extensions":[]}
				}
			""".trimIndent()
		}

		val result = kotlinx.coroutines.runBlocking {
			ExternalExtensionRepoRepository(client).validateStore("https://example.com/index.min.json")
		}

		assertEquals("Community Store", result.store.name)
		assertEquals("CS", result.store.shortName)
		assertEquals("feed1234", result.store.fingerprint)
	}

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
	fun `resolveApkUrl works with index pb url`() {
		val repository = ExternalExtensionRepoRepository(OkHttpClient())
		val resolved = repository.resolveApkUrl(
			repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.pb",
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

	@Test
	fun `newer extension version code is an update`() {
		assertTrue(repoEntry(versionCode = 11, versionName = "1.4.1").isNewerThan(installed()))
		assertFalse(repoEntry(versionCode = 10, versionName = "1.4.1").isNewerThan(installed()))
	}

	@Test
	fun `newer source api version is an update even with same extension version code`() {
		assertTrue(repoEntry(versionCode = 10, versionName = "1.5.0").isNewerThan(installed()))
	}

	@Test
	fun `loaded extension update detection uses the same version rules`() {
		val loaded = MihonLoadResult.Success(
			pkgName = "example.extension",
			appName = "Example",
			versionCode = 10,
			versionName = "1.4.1",
			libVersion = 1.4,
			lang = "en",
			isNsfw = false,
			sources = emptyList(),
		)
		assertTrue(repoEntry(versionCode = 10, versionName = "1.5.0").isNewerThan(loaded))
		assertFalse(repoEntry(versionCode = 10, versionName = "1.4.1").isNewerThan(loaded))
	}

	private fun repoEntry(versionCode: Long, versionName: String) = ExternalExtensionRepoEntry(
		name = "Example",
		packageName = "example.extension",
		apkName = "example.apk",
		versionCode = versionCode,
		versionName = versionName,
	)

	private fun installed() = MihonExtensionInfo(
		pkgName = "example.extension",
		appName = "Example",
		versionCode = 10,
		versionName = "1.4.1",
		libVersion = 1.4,
		lang = "en",
		isNsfw = false,
		sourceClassName = "ExampleSource",
		apkPath = "/example.apk",
	)

	private fun clientReturning(body: (String) -> String?): OkHttpClient =
		OkHttpClient.Builder()
			.addInterceptor { chain ->
				val request = chain.request()
				val responseBody = body(request.url.encodedPath)
				Response.Builder()
					.request(request)
					.protocol(Protocol.HTTP_1_1)
					.code(if (responseBody == null) 404 else 200)
					.message(if (responseBody == null) "Not Found" else "OK")
					.body((responseBody ?: "").toResponseBody())
					.build()
			}
			.build()
}
