package org.haziffe.dropsauce.tracker.ui.feed.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.ui.BaseListAdapter
import org.haziffe.dropsauce.core.ui.list.OnListItemClickListener
import org.haziffe.dropsauce.databinding.ItemListGroupBinding
import org.haziffe.dropsauce.list.ui.adapter.ListHeaderClickListener
import org.haziffe.dropsauce.list.ui.adapter.ListItemType
import org.haziffe.dropsauce.list.ui.adapter.mangaGridItemAD
import org.haziffe.dropsauce.list.ui.model.ListHeader
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.list.ui.model.MangaListModel
import org.haziffe.dropsauce.list.ui.size.ItemSizeResolver
import org.haziffe.dropsauce.tracker.ui.feed.model.UpdatedMangaHeader

fun updatedMangaAD(
	sizeResolver: ItemSizeResolver,
	listener: OnListItemClickListener<MangaListModel>,
	headerClickListener: ListHeaderClickListener,
) = adapterDelegateViewBinding<UpdatedMangaHeader, ListModel, ItemListGroupBinding>(
	{ layoutInflater, parent -> ItemListGroupBinding.inflate(layoutInflater, parent, false) },
) {

	val adapter = BaseListAdapter<ListModel>()
		.addDelegate(ListItemType.MANGA_GRID, mangaGridItemAD(sizeResolver, listener))
	binding.recyclerView.adapter = adapter
	binding.buttonMore.setOnClickListener { v ->
		headerClickListener.onListHeaderClick(ListHeader(0, payload = item), v)
	}
	binding.textViewTitle.setText(R.string.updates)
	binding.buttonMore.setText(R.string.more)

	bind {
		adapter.items = item.list
	}
}
