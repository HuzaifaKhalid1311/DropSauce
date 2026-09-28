package org.koitharu.kotatsu.explore.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.koitharu.kotatsu.explore.ui.adapter.sourceIconInsetPx
import java.io.File

class ExploreSourceKindSelectorTest {

	@Test
	fun `source kind selector fills the header gap with two equal tabs`() {
		val layout = layout("layout_explore_header.xml")

		assertTrue(layout.contains("""android:id="@+id/tabs_kind""""))
		assertTrue(layout.contains("""android:layout_width="0dp""""))
		assertTrue(layout.contains("""android:layout_weight="1""""))
		assertTrue(layout.contains("""app:tabGravity="fill""""))
		assertTrue(layout.contains("""app:tabMode="fixed""""))
		assertFalse(layout.contains("ChipGroup"))
		assertFalse(layout.contains("chip_kind_manga"))
		assertFalse(layout.contains("chip_kind_novel"))
	}

	@Test
	fun `source kind selector has no tap highlight`() {
		val layout = layout("layout_explore_header.xml")

		assertTrue(layout.contains("""app:tabRippleColor="@null""""))
	}

	@Test
	fun `novel icons receive adaptive safe zone inset`() {
		assertEquals(8, sourceIconInsetPx(iconSizePx = 80, isNovel = true))
		assertEquals(4, sourceIconInsetPx(iconSizePx = 40, isNovel = true))
		assertEquals(0, sourceIconInsetPx(iconSizePx = 80, isNovel = false))
	}

	@Test
	fun `manage action uses text and balanced header slots`() {
		val layout = layout("layout_explore_header.xml")

		assertTrue(layout.contains("""android:text="@string/manage""""))
		assertFalse(layout.contains("""app:icon="@drawable/ic_extension_manage""""))
		assertEquals(2, Regex("""android:layout_width="@dimen/explore_header_side_width"""").findAll(layout).count())
	}

	private fun layout(name: String): String {
		return sequenceOf(
			File("src/main/res/layout", name),
			File("app/src/main/res/layout", name),
		).firstOrNull(File::isFile)?.readText()
			?: error("Cannot find production layout: $name")
	}
}
