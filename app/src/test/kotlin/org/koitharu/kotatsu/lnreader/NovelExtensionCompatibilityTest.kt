package org.koitharu.kotatsu.lnreader

import eu.kanade.tachiyomi.source.NovelSource
import eu.kanade.tachiyomi.source.RateLimited
import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@Suppress("DEPRECATION")
class NovelExtensionCompatibilityTest {

	@Test
	fun `tsundoku novel source api 1_6 linkage surface exists`() {
		assertNotNull(NovelSource::class.java)
		assertNotNull(RateLimited::class.java)
		assertNotNull(SourceTracker::class.java)
		assertNotNull(SChapter::class.java.getMethod("getLocked"))
		assertNotNull(SChapter::class.java.getMethod("getRead"))
		assertNotNull(SChapter::class.java.getMethod("getLast_page_read"))
		assertNotNull(SManga::class.java.getMethod("getAltTitles"))
	}

	@Test
	fun `lnreader fetch keeps cloudflare error and plugin ownership`() {
		val host = File("src/main/assets/lnreader-host.js").readText()

		assertTrue(host.contains("pluginId: pluginId || null"))
		assertTrue(host.contains("error.isCloudFlare = !!r.isCloudFlare"))
		assertTrue(host.contains("if (e && e.isCloudFlare) throw e"))
	}

	@Test
	fun `lnreader fetch preserves browser request body semantics`() {
		val host = File("src/main/assets/lnreader-host.js").readText()
		val jsHost = File("src/main/kotlin/org/koitharu/kotatsu/lnreader/js/JsHost.kt").readText()

		assertTrue(host.contains("body instanceof FormData"))
		assertTrue(host.contains("formData.push([key, value])"))
		assertTrue(host.contains("body instanceof URLSearchParams"))
		assertTrue(host.contains("application/x-www-form-urlencoded;charset=UTF-8"))
		assertTrue(host.contains("headers.Referer = referrer"))
		assertTrue(jsHost.contains("MultipartBody.Builder()"))
		assertTrue(jsHost.contains("method in METHODS_REQUIRING_BODY"))
	}

	@Test
	fun `lnreader cloudflare resolver verifies an already usable webview session`() {
		val activity = File(
			"src/main/kotlin/org/koitharu/kotatsu/browser/cloudflare/CloudFlareActivity.kt",
		).readText()

		assertTrue(activity.contains("startsWith(LN_SOURCE_PREFIX)"))
		assertTrue(activity.contains("CloudFlareHelper.getClearanceCookie(cookieJar, it)"))
		assertTrue(activity.contains("onCheckPassed()"))
	}

	@Test
	fun `lnreader fetch and cloudflare webview share one user agent`() {
		val jsHost = File("src/main/kotlin/org/koitharu/kotatsu/lnreader/js/JsHost.kt").readText()
		val images = File("src/main/kotlin/org/koitharu/kotatsu/core/image/MangaSourceHeaderInterceptor.kt").readText()

		assertTrue(jsHost.contains("webViewExecutor.defaultUserAgent ?: UserAgents.FIREFOX_MOBILE"))
		assertTrue(jsHost.contains("replace(Regex(\"; Android .*?\\\\)\"), \"; Android 10; K)\")"))
		assertTrue(jsHost.contains("builder.header(CommonHeaders.USER_AGENT, userAgent)"))
		assertTrue(images.contains("set(CommonHeaders.USER_AGENT, lnUserAgent)"))
		assertTrue(images.contains("webViewExecutor.get().defaultUserAgent ?: UserAgents.FIREFOX_MOBILE"))
	}

	@Test
	fun `lnreader resolver opens protected document and avoids misleading plugin captcha text`() {
		val router = File("src/main/kotlin/org/koitharu/kotatsu/core/nav/AppRouter.kt").readText()
		val jsHost = File("src/main/kotlin/org/koitharu/kotatsu/lnreader/js/JsHost.kt").readText()

		assertTrue(router.contains("preservePage = exception.source.name.startsWith(\"LN_\")"))
		assertTrue(router.contains("if (preservePage && !isAsset)"))
		assertTrue(jsHost.contains("err.contains(GENERIC_PLUGIN_CAPTCHA_ERROR, ignoreCase = true)"))
		assertTrue(jsHost.contains("It may be offline, changed, or require browser verification."))
	}
}
