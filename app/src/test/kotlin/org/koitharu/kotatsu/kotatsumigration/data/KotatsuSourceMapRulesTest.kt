package org.koitharu.kotatsu.kotatsumigration.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the hand-edited url rules in the baked source map: every rule compiles and only uses groups it has. */
class KotatsuSourceMapRulesTest {

	@Test
	fun `every url rule compiles and references existing groups`() {
		val entries = Json.parseToJsonElement(File("src/main/res/raw/kotatsu_source_map.json").readText())
			.jsonObject.getValue("entries").jsonObject
		var rules = 0
		for ((name, entry) in entries) {
			for (key in listOf("mangaUrl", "chapterUrl")) {
				for (rule in (entry as JsonObject)[key]?.jsonArray.orEmpty()) {
					val obj = rule.jsonObject
					val source = Regex((obj["public"] ?: obj.getValue("url")).jsonPrimitive.content)
					Regex(obj.getValue("url").jsonPrimitive.content)
					val groups = source.toPattern().matcher("").groupCount()
					val used = Regex("""\$(\d)""").findAll(obj.getValue("to").jsonPrimitive.content)
						.maxOfOrNull { it.groupValues[1].toInt() } ?: 0
					assertTrue("$name.$key uses $$used but has $groups groups", used <= groups)
					rules++
				}
			}
		}
		assertTrue(rules > 0)
	}
}
