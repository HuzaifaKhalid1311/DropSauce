package org.koitharu.kotatsu.settings.developer

import org.junit.Assert.assertEquals
import org.junit.Test

class DeveloperTestModelsTest {

	@Test
	fun `required failure makes extension an error`() {
		val stages = listOf(
			stage(DeveloperTestStageStatus.PASSED),
			stage(DeveloperTestStageStatus.FAILED),
		)

		assertEquals(DeveloperExtensionStatus.ERROR, stages.extensionStatus())
	}

	@Test
	fun `interactive failure makes extension blocked`() {
		val stages = listOf(
			stage(DeveloperTestStageStatus.PASSED),
			stage(DeveloperTestStageStatus.BLOCKED),
		)

		assertEquals(DeveloperExtensionStatus.BLOCKED, stages.extensionStatus())
	}

	@Test
	fun `skipped optional stage still passes`() {
		val stages = listOf(
			stage(DeveloperTestStageStatus.PASSED),
			stage(DeveloperTestStageStatus.SKIPPED),
		)

		assertEquals(DeveloperExtensionStatus.PASSED, stages.extensionStatus())
	}

	@Test
	fun `explicit pending and running states override stage aggregation`() {
		val pending = result(DeveloperExtensionStatus.PENDING)
		val running = result(DeveloperExtensionStatus.RUNNING)

		assertEquals(DeveloperExtensionStatus.PENDING, pending.status)
		assertEquals(DeveloperExtensionStatus.RUNNING, running.status)
	}

	private fun result(state: DeveloperExtensionStatus) = DeveloperExtensionTestResult(
		packageName = "package",
		extensionName = "Extension",
		sourceName = "Source",
		language = "English",
		stages = emptyList(),
		durationMillis = 0,
		state = state,
	)

	private fun stage(status: DeveloperTestStageStatus) = DeveloperTestStageResult(
		name = "stage",
		status = status,
		message = null,
		durationMillis = 0,
	)
}
