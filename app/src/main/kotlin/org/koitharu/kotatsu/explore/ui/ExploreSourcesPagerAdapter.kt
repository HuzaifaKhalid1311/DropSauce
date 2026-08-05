package org.koitharu.kotatsu.explore.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.koitharu.kotatsu.databinding.ItemExploreSourcesPageBinding

/**
 * Two fixed pages for Explore's manga/novel switch. The item view type is the position, so each page
 * view is created exactly once and never recycled into the other page — both stay alive, which is what
 * makes switching instant.
 */
class ExploreSourcesPagerAdapter(
	private val onPageCreated: (recyclerView: RecyclerView, isNovel: Boolean) -> Unit,
) : RecyclerView.Adapter<ExploreSourcesPagerAdapter.PageHolder>() {

	override fun getItemCount() = 2

	override fun getItemViewType(position: Int) = position

	override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
		val binding = ItemExploreSourcesPageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
		onPageCreated(binding.recyclerView, viewType == 1)
		return PageHolder(binding)
	}

	override fun onBindViewHolder(holder: PageHolder, position: Int) = Unit

	class PageHolder(binding: ItemExploreSourcesPageBinding) : RecyclerView.ViewHolder(binding.root)
}
