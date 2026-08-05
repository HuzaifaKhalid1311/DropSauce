package org.koitharu.kotatsu.core.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoUiScaleTest {

	@Test
	fun `reference device is never scaled`() {
		assertEquals(1f, autoUiScale(427), 0f)
		assertEquals(1f, autoUiScale(426), 0f)
		assertEquals(1f, autoUiScale(424), 0f)
	}

	@Test
	fun `wider screens and tablets are never scaled up`() {
		assertEquals(1f, autoUiScale(600), 0f)
		assertEquals(1f, autoUiScale(840), 0f)
	}

	@Test
	fun `a common narrow phone gets the reference canvas back`() {
		val scale = autoUiScale(360)

		assertEquals(360 / 424f, scale, 0.005f)
		assertTrue("canvas should reach the design width", 360 / scale >= 420f)
	}

	@Test
	fun `slightly narrow phones are only nudged`() {
		val scale = autoUiScale(411)

		assertTrue("expected a gentle scale, was $scale", scale > 0.95f && scale < 1f)
	}

	@Test
	fun `scale never drops below the floor`() {
		assertEquals(0.80f, autoUiScale(240), 0f)
		assertEquals(0.80f, autoUiScale(1), 0f)
	}

	@Test
	fun `unknown configuration is left alone`() {
		assertEquals(1f, autoUiScale(0), 0f)
		assertEquals(1f, autoUiScale(-1), 0f)
	}
}
