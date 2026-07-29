package org.koitharu.kotatsu.local.data.input

import org.junit.Assert.assertEquals
import org.junit.Test

class EpubParserTest {

	@Test
	fun `leading byte order mark is removed from xml input`() {
		val xml = "\uFEFF<?xml version=\"1.0\"?><container/>"

		assertEquals(
			"<?xml version=\"1.0\"?><container/>",
			EpubParser.normalizeXmlInput(xml),
		)
	}

	@Test
	fun `xml input without byte order mark is unchanged`() {
		val xml = "<?xml version=\"1.0\"?><container/>"

		assertEquals(xml, EpubParser.normalizeXmlInput(xml))
	}
}
