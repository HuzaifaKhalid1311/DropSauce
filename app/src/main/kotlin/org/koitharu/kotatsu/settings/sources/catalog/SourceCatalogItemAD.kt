package org.haziffe.dropsauce.settings.sources.catalog

import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.model.getSummary
import org.haziffe.dropsauce.core.model.getTitle
import org.haziffe.dropsauce.core.model.isBroken
import org.haziffe.dropsauce.core.model.isExternalSource
import org.haziffe.dropsauce.core.model.MangaSource
import org.haziffe.dropsauce.core.ui.image.FaviconDrawable
import org.haziffe.dropsauce.core.ui.list.OnListItemClickListener
import org.haziffe.dropsauce.core.util.ext.drawableStart
import org.haziffe.dropsauce.core.util.ext.getThemeDimensionPixelOffset
import org.haziffe.dropsauce.core.util.ext.setTextAndVisible
import org.haziffe.dropsauce.databinding.ItemEmptyCardBinding
import org.haziffe.dropsauce.databinding.ItemSourceCatalogBinding
import org.haziffe.dropsauce.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

interface ExtensionActionListener {
	fun onExtensionActionClick(item: SourceCatalogItem.Extension)
	fun onExtensionSettingsClick(item: SourceCatalogItem.Extension)
	fun onExtensionItemClick(item: SourceCatalogItem.Extension)
}

fun sourceCatalogItemSourceAD(
	listener: OnListItemClickListener<SourceCatalogItem.Source>
) = adapterDelegateViewBinding<SourceCatalogItem.Source, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewAdd.setOnClickListener { v ->
		listener.onItemLongClick(item, v)
	}
	binding.root.setOnClickListener { v ->
		listener.onItemClick(item, v)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	val compactEndPadding = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0)

	bind {
		binding.imageViewAdd.isVisible = item.isAddAvailable
		binding.viewAddDivider.isVisible = item.isAddAvailable
		binding.imageViewIcon.applyExternalSourceStyle(item.source.isExternalSource())
		binding.root.updatePaddingRelative(
			end = if (item.isAddAvailable) compactEndPadding else basePadding,
		)
		binding.textViewTitle.text = item.source.getTitle(context)
		binding.textViewDescription.text = item.source.getSummary(context)
		binding.textViewDescription.drawableStart = if (item.source.isBroken) {
			ContextCompat.getDrawable(context, R.drawable.ic_off_small)
		} else {
			null
		}
		FaviconDrawable(context, R.style.FaviconDrawable_Small, item.source.name)
		binding.imageViewIcon.setImageAsync(item.source)
	}
}

fun sourceCatalogItemExtensionAD(
	listener: ExtensionActionListener,
) = adapterDelegateViewBinding<SourceCatalogItem.Extension, ListModel, ItemSourceCatalogBinding>(
	{ layoutInflater, parent ->
		ItemSourceCatalogBinding.inflate(layoutInflater, parent, false)
	},
) {

	binding.imageViewAdd.setOnClickListener {
		listener.onExtensionActionClick(item)
	}
	binding.imageViewSettings.setOnClickListener {
		listener.onExtensionSettingsClick(item)
	}
	binding.root.setOnClickListener {
		listener.onExtensionItemClick(item)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	val compactEndPadding = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0)

	bind {
		binding.imageViewAdd.isVisible = true
		binding.imageViewSettings.isVisible = item.action != SourceCatalogItem.Extension.Action.INSTALL && item.sourceName != null
		binding.viewAddDivider.isVisible = true
		binding.root.updatePaddingRelative(end = compactEndPadding)
		binding.imageViewAdd.setImageResource(item.action.iconRes)
		binding.imageViewAdd.contentDescription = context.getString(item.action.titleRes)
		binding.imageViewAdd.tooltipText = context.getString(item.action.titleRes)
		binding.textViewTitle.text = item.title
		binding.textViewDescription.text = item.subtitle
		binding.textViewDescription.drawableStart = null
		binding.imageViewIcon.applyExternalSourceStyle(true)
		val sourceIconName = item.sourceIconName
		val iconUrl = item.iconUrl
		if (sourceIconName != null) {
			binding.imageViewIcon.setImageAsync(MangaSource(sourceIconName))
		} else if (iconUrl != null) {
			binding.imageViewIcon.setImageFromUrlAsync(
				url = iconUrl,
				fallbackName = item.packageName,
			)
		} else {
			binding.imageViewIcon.setImageDrawable(
				FaviconDrawable(
					context = context,
					styleResId = R.style.FaviconDrawable_Small,
					name = item.packageName,
				),
			)
		}
	}
}

fun sourceCatalogItemHintAD() = adapterDelegateViewBinding<SourceCatalogItem.Hint, ListModel, ItemEmptyCardBinding>(
	{ inflater, parent -> ItemEmptyCardBinding.inflate(inflater, parent, false) },
) {

	binding.buttonRetry.isVisible = false

	bind {
		binding.icon.setImageAsync(item.icon)
		binding.textPrimary.setText(item.title)
		binding.textSecondary.setTextAndVisible(item.text)
	}
}
