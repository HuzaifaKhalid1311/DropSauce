package org.koitharu.kotatsu.history.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.list.domain.ListSortOrder
import java.time.LocalDate
import java.time.ZoneOffset

class HistorySectionTest {

	private val utc = ZoneOffset.UTC

	@Test
	fun `date sections split today, yesterday, week, this month and older months monotonically`() {
		val today = LocalDate.of(2026, 5, 20)
		val resolver = HistorySectionResolver(ListSortOrder.Type.LAST_READ, today, utc)

		fun at(year: Int, month: Int, day: Int) =
			LocalDate.of(year, month, day).atTime(12, 0).toInstant(utc).toEpochMilli()

		assertEquals("today", resolver.resolve(0L, at(2026, 5, 25), 0f)) // future clock skew stays in today
		assertEquals("today", resolver.resolve(0L, at(2026, 5, 20), 0f))
		assertEquals("yesterday", resolver.resolve(0L, at(2026, 5, 19), 0f))
		assertEquals("week", resolver.resolve(0L, at(2026, 5, 14), 0f))
		assertEquals("month", resolver.resolve(0L, at(2026, 5, 13), 0f))
		assertEquals("m:2026-04", resolver.resolve(0L, at(2026, 4, 30), 0f))
		assertEquals("unknown", resolver.resolve(0L, 0L, 0f))
	}

	@Test
	fun `early month skips this-month bucket when last 7 days already crosses into previous month`() {
		val today = LocalDate.of(2026, 5, 3)
		val resolver = HistorySectionResolver(ListSortOrder.Type.LAST_READ, today, utc)

		fun at(month: Int, day: Int) =
			LocalDate.of(2026, month, day).atTime(12, 0).toInstant(utc).toEpochMilli()

		assertEquals("week", resolver.resolve(0L, at(4, 28), 0f))
		assertEquals("m:2026-04", resolver.resolve(0L, at(4, 25), 0f))
	}

	@Test
	fun `progress sections bucket planned, reading, completed and unknown`() {
		val resolver = HistorySectionResolver(ListSortOrder.Type.PROGRESS, LocalDate.of(2026, 5, 20), utc)

		assertEquals("unknown", resolver.resolve(0L, 0L, -1f))
		assertEquals("planned", resolver.resolve(0L, 0L, 0f))
		assertEquals("reading", resolver.resolve(0L, 0L, 0.5f))
		assertEquals("completed", resolver.resolve(0L, 0L, 1f))
		assertNull(HistorySectionResolver(ListSortOrder.Type.ALPHABETICAL).resolve(0L, 0L, 0.5f))
	}

	@Test
	fun `section is fully loaded only when count is exact and all ids are present`() {
		val base = HistorySection(
			key = "today",
			count = 4,
			isCountExact = true,
			isCollapsed = false,
			itemIds = listOf(1L, 2L, 3L, 4L),
			previews = emptyList(),
		)
		assertTrue(base.isFullyLoaded)
		assertFalse(base.copy(itemIds = listOf(1L, 2L)).isFullyLoaded)
		assertFalse(base.copy(isCountExact = false).isFullyLoaded)
	}
}
