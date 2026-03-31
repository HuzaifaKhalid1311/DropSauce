package org.haziffe.dropsauce.settings.storage

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.haziffe.dropsauce.core.ui.list.OnListItemClickListener
import org.haziffe.dropsauce.core.util.ext.textAndVisible
import org.haziffe.dropsauce.databinding.ItemStorageBinding

fun directoryAD(
	clickListener: OnListItemClickListener<DirectoryModel>,
) = adapterDelegateViewBinding<DirectoryModel, DirectoryModel, ItemStorageBinding>(
	{ layoutInflater, parent -> ItemStorageBinding.inflate(layoutInflater, parent, false) },
) {

	binding.root.setOnClickListener { v -> clickListener.onItemClick(item, v) }

	bind {
		binding.textViewTitle.text = item.title ?: getString(item.titleRes)
		binding.textViewSubtitle.textAndVisible = item.file?.absolutePath
		binding.imageViewIndicator.isChecked = item.isChecked
	}
}
