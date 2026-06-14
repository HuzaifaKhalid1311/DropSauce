package org.koitharu.kotatsu.list.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import org.koitharu.kotatsu.core.prefs.ProgressIndicatorMode.PERCENT_READ

class ReadingProgressTest {

	@Test
	fun `test valid progress`() {
		val progress = ReadingProgress(
			percent = 0.4f,
			totalChapters = 10,
			mode = PERCENT_READ,
		)
		assertEquals(true, progress.isValid())
	}
}
