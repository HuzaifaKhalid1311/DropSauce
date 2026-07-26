package org.koitharu.kotatsu.settings.sources.catalog

import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalExtensionStoreDecodeTest {

	private val json = Json { ignoreUnknownKeys = true }
	private val protoBuf = ProtoBuf { }

	private val sampleStore = NetworkExtensionStore(
		name = "Sample",
		badgeLabel = "S",
		signingKey = "deadbeef",
		extensionList = NetworkExtensionStore.ExtensionList(
			listOf(
				NetworkExtensionStore.Extension(
					name = "Cool Source",
					packageName = "eu.kanade.tachiyomi.extension.en.cool",
					resources = NetworkExtensionStore.Resources(
						apkUrl = "https://repo.example/apk/cool.apk",
						iconUrl = "https://repo.example/icon/cool.png",
					),
					extensionLib = "1.5",
					versionCode = 12,
					versionName = "1.5.3",
					contentWarning = NetworkExtensionStore.ContentWarning.NSFW,
					sources = listOf(
						NetworkExtensionStore.Source(id = 42L, name = "Cool", language = "en"),
					),
				),
			),
		),
	)

	@Test
	fun `protobuf round-trip preserves field numbering`() {
		val bytes = protoBuf.encodeToByteArray(sampleStore)
		val decoded = protoBuf.decodeFromByteArray<NetworkExtensionStore>(bytes)
		assertEquals("Sample", decoded.name)
		assertEquals("deadbeef", decoded.signingKey)
		assertEquals(1, decoded.extensionList!!.extensions.size)
	}

	@Test
	fun `extension maps onto repo entry with absolute urls and nsfw flag`() {
		val entry = sampleStore.extensionList!!.extensions.first().toRepoEntry()
		assertEquals("eu.kanade.tachiyomi.extension.en.cool", entry.packageName)
		assertEquals("https://repo.example/apk/cool.apk", entry.apkName) // absolute; resolveApkUrl passes through
		assertEquals("https://repo.example/icon/cool.png", entry.iconUrl)
		assertEquals("en", entry.lang)
		assertEquals(12L, entry.versionCode)
		assertEquals(1, entry.isNsfw) // NSFW >= MIXED
		assertEquals("42", entry.sources.single().id)
	}

	@Test
	fun `safe content warning is not nsfw`() {
		val entry = sampleStore.extensionList!!.extensions.first()
			.copy(contentWarning = NetworkExtensionStore.ContentWarning.SAFE)
			.toRepoEntry()
		assertEquals(0, entry.isNsfw)
	}

	@Test
	fun `json store decodes with CONTENT_WARNING string names`() {
		val body = """
			{"name":"S","badgeLabel":"S","signingKey":"k","extensionList":{"extensions":[
			{"name":"X","packageName":"p","resources":{"apkUrl":"https://a/x.apk","iconUrl":"https://a/x.png"},
			"extensionLib":"1.4","versionCode":1,"versionName":"1.4.0","contentWarning":"CONTENT_WARNING_MIXED",
			"sources":[{"id":7,"name":"X","language":"fr"}]}]}}
		""".trimIndent()
		val store = json.decodeFromString<NetworkExtensionStore>(body)
		val entry = store.extensionList!!.extensions.first().toRepoEntry()
		assertEquals("p", entry.packageName)
		assertEquals(1, entry.isNsfw) // MIXED
		assertEquals("fr", entry.lang)
	}

	@Test
	fun `parseRepoInfo reads new top-level store shape`() {
		val info = parseRepoInfo(
			"https://repo.example",
			"""{"name":"NewRepo","badgeLabel":"NR","signingKey":"abcd","contact":{"website":"https://site.example","discord":"https://discord.gg/example"}}""",
		)!!
		assertEquals("NewRepo", info.name)
		assertEquals("abcd", info.fingerprint)
		assertEquals("NR", info.shortName)
		assertEquals("https://site.example", info.website)
		assertEquals("https://discord.gg/example", info.discord)
	}

	@Test
	fun `parseRepoInfo still reads legacy meta shape`() {
		val info = parseRepoInfo("https://repo.example", """{"meta":{"name":"Old","signingKeyFingerprint":"ffff"}}""")!!
		assertEquals("Old", info.name)
		assertEquals("ffff", info.fingerprint)
	}

	@Test
	fun `parseRepoInfo returns null for a plain array`() {
		assertNull(parseRepoInfo("https://repo.example", """[{"name":"x"}]"""))
	}
}
