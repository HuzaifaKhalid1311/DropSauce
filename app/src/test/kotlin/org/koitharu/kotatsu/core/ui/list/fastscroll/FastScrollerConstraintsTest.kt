package org.koitharu.kotatsu.core.ui.list.fastscroll

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollerConstraintsTest {

	@Test
	fun `scroller follows a sibling recycler view`() {
		assertEquals(42, fastScrollerConstraintTargetId(isRecyclerViewSibling = true, recyclerViewId = 42))
	}

	@Test
	fun `scroller follows its parent when recycler view is nested`() {
		assertEquals(0, fastScrollerConstraintTargetId(isRecyclerViewSibling = false, recyclerViewId = 42))
	}
}
