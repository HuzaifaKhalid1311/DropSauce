package org.koitharu.kotatsu.settings.sources.catalog

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseListAdapter
import org.koitharu.kotatsu.core.ui.list.fastscroll.FastScroller
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.getThemeDimensionPixelOffset
import org.koitharu.kotatsu.databinding.ItemButtonFooterBinding
import org.koitharu.kotatsu.list.ui.adapter.ListItemType
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.adapter.listHeaderAD
import org.koitharu.kotatsu.list.ui.adapter.loadingStateAD
import org.koitharu.kotatsu.list.ui.model.ButtonFooter
import org.koitharu.kotatsu.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

class SourcesCatalogAdapter(
	extensionActionListener: ExtensionActionListener,
	headerClickListener: ListHeaderClickListener,
	onUpdateAll: () -> Unit,
) : BaseListAdapter<ListModel>(), FastScroller.SectionIndexer {

	init {
		addDelegate(ListItemType.NAV_ITEM, sourceCatalogItemExtensionAD(extensionActionListener))
		addDelegate(ListItemType.HINT_EMPTY, sourceCatalogItemHintAD())
		addDelegate(ListItemType.HEADER, listHeaderAD(headerClickListener))
		addDelegate(ListItemType.STATE_LOADING, loadingStateAD())
		addDelegate(ListItemType.FOOTER_BUTTON, updateAllFooterAD(onUpdateAll))
	}

	override fun getSectionText(context: Context, position: Int): CharSequence? {
		return (items.getOrNull(position) as? SourceCatalogItem.Extension)?.title?.take(1)
	}
}

private fun updateAllFooterAD(
	onUpdateAll: () -> Unit,
) = adapterDelegateViewBinding<ButtonFooter, ListModel, ItemButtonFooterBinding>(
	{ inflater, parent -> ItemButtonFooterBinding.inflate(inflater, parent, false) },
) {
	// Same top-bar colours as the extension rows' action buttons; the footer layout is shared, so only here
	binding.button.backgroundTintList = ContextCompat.getColorStateList(context, R.color.bg_extension_action_button)
	binding.button.setTextColor(context.getThemeColor(R.attr.colorTopBarIcon))
	binding.button.strokeWidth = 0
	binding.root.updatePaddingRelative(
		start = context.getThemeDimensionPixelOffset(appcompatR.attr.listPreferredItemPaddingStart),
		end = context.getThemeDimensionPixelOffset(appcompatR.attr.listPreferredItemPaddingEnd),
	)
	binding.button.setOnClickListener { onUpdateAll() }
	bind { binding.button.setText(item.textResId) }
}
