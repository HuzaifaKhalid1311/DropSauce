package org.koitharu.kotatsu.details.ui.pager

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.details.ui.pager.bookmarks.BookmarksFragment
import org.koitharu.kotatsu.details.ui.pager.chapters.ChaptersFragment
import org.koitharu.kotatsu.details.ui.pager.pages.PagesFragment

class ChaptersPagesAdapter(
	fragment: Fragment,
	val isPagesTabEnabled: Boolean,
	private val isEpub: Boolean,
) : FragmentStateAdapter(fragment),
	TabLayoutMediator.TabConfigurationStrategy {

	override fun getItemCount(): Int = if (isPagesTabEnabled) 3 else 2

	override fun createFragment(position: Int): Fragment = when (position) {
		0 -> ChaptersFragment()
		1 -> if (isPagesTabEnabled) PagesFragment() else BookmarksFragment()
		2 -> BookmarksFragment()
		else -> throw IllegalArgumentException("Invalid position $position")
	}

	override fun onConfigureTab(tab: TabLayout.Tab, position: Int) {
		val isPages = position == 1 && isPagesTabEnabled
		// Novels store text highlights in the bookmarks tab.
		when {
			position == 0 -> tab.setIcon(R.drawable.ic_list).setContentDescription(R.string.chapters)
			isPages -> tab.setIcon(R.drawable.ic_grid_outline).setContentDescription(R.string.pages)
			isEpub -> tab.setIcon(R.drawable.ic_ink_highlighter).setContentDescription(R.string.highlights)
			else -> tab.setIcon(R.drawable.ic_bookmark).setContentDescription(R.string.bookmarks)
		}
	}
}
