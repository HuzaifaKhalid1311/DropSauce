package org.koitharu.kotatsu.core.logs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogcatLineFilterTest {

	private val filter = LogcatLineFilter(processTag = "ziffe.dropsauce")

	@Test
	fun `app lines are kept in compact form`() {
		assertEquals(
			"07:31:28.202 I Reader: open MIHON_1 manga id=5",
			filter.compact("09-30 07:31:28.202 14284 14290 I Reader  : open MIHON_1 manga id=5"),
		)
		// Extension tags may contain spaces; the message may contain ": ".
		assertEquals(
			"07:31:28.202 E Comic Asura: HTTP error: 403",
			filter.compact("09-30 07:31:28.202 14284 14290 E Comic Asura: HTTP error: 403"),
		)
	}

	@Test
	fun `android noise is dropped`() {
		assertNull(filter.compact("09-30 07:31:00.420 14284 14284 I AdrenoVK-0: Driver info"))
		assertNull(filter.compact("09-30 07:31:00.420 14284 14284 W Paint   : The elegant text height cannot be turned off."))
		assertNull(filter.compact("09-30 07:31:00.420 14284 14284 I ziffe.dropsauce: Background concurrent mark compact GC freed 1MB"))
		assertNull(filter.compact("09-30 07:31:00.420 14284 14284 W ziffe.dropsauce: Unsupported class loader: java.lang.Class<X>"))
		assertNull(filter.compact("--------- beginning of main"))
		assertNull(filter.compact("09-30 07:31:00.420 14284 14290 D CURL    : curl -X GET -H \"Cookie: session=abc\" \"https://x\""))
	}

	@Test
	fun `runtime and framework warnings are kept`() {
		assertEquals(
			"07:31:00.420 W ziffe.dropsauce: Throwing OutOfMemoryError",
			filter.compact("09-30 07:31:00.420 14284 14284 W ziffe.dropsauce: Throwing OutOfMemoryError"),
		)
		assertEquals(
			"07:31:00.420 E System: Uncaught exception thrown by finalizer",
			filter.compact("09-30 07:31:00.420 14284 14284 E System  : Uncaught exception thrown by finalizer"),
		)
	}

	@Test
	fun `unparsable lines pass through`() {
		assertEquals("=== CRASH: x ===", filter.compact("=== CRASH: x ==="))
	}
}
