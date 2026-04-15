package org.koitharu.kotatsu.details.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailsViewModelBranchSelectionTest {

	@Test
	fun keepsSelectedBranchWhenStillAvailable() {
		val branch = resolveSelectedBranch(
			selectedBranch = "English",
			availableBranches = setOf("English", "Spanish"),
			preferredBranch = "Spanish",
		)
		assertEquals("English", branch)
	}

	@Test
	fun switchesToPreferredWhenSelectedBranchMissing() {
		val branch = resolveSelectedBranch(
			selectedBranch = "English",
			availableBranches = setOf("Spanish", "French"),
			preferredBranch = "Spanish",
		)
		assertEquals("Spanish", branch)
	}

	@Test
	fun returnsNullWhenNoBranchesAvailable() {
		val branch = resolveSelectedBranch(
			selectedBranch = "English",
			availableBranches = emptySet(),
			preferredBranch = "Spanish",
		)
		assertNull(branch)
	}
}
