package org.koitharu.kotatsu.list.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListSortOrderTest {

	@Test
	fun everyTypeHasBothDirections() {
		for (type in ListSortOrder.Type.entries) {
			// toSortOrder() throws if a direction is missing, which would crash the sort sheet
			assertEquals(type, type.toSortOrder(isAscending = true).type)
			assertTrue(type.toSortOrder(isAscending = true).isAscending)
			assertEquals(type, type.toSortOrder(isAscending = false).type)
			assertTrue(!type.toSortOrder(isAscending = false).isAscending)
		}
	}

	@Test
	fun directionChangesTheOrderBy() {
		for (type in ListSortOrder.Type.entries) {
			val ascending = orderBy(type.toSortOrder(isAscending = true))
			val descending = orderBy(type.toSortOrder(isAscending = false))
			assertTrue(ascending.isNotBlank())
			assertNotEquals("$type sorts the same way in both directions", ascending, descending)
		}
	}

	@Test
	fun historyDropsDateAdded() {
		assertTrue(ListSortOrder.Type.DATE_ADDED !in ListSortOrder.HISTORY)
		assertTrue(ListSortOrder.Type.DATE_ADDED in ListSortOrder.FAVORITES)
	}

	private fun orderBy(order: ListSortOrder) = order.toOrderBy(
		dateAdded = "history.created_at",
		lastRead = "history.updated_at",
		progress = "history.percent",
	)
}
