package org.koitharu.kotatsu.settings.sources.catalog

import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.appcompat.widget.TooltipCompat
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.ui.image.FaviconDrawable
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getThemeDimensionPixelOffset
import org.koitharu.kotatsu.core.util.ext.setTextAndVisible
import org.koitharu.kotatsu.databinding.ItemEmptyCardBinding
import org.koitharu.kotatsu.databinding.ItemSourceCatalogBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import androidx.appcompat.R as appcompatR

interface ExtensionActionListener {
	fun onExtensionActionClick(item: SourceCatalogItem.Extension)
	fun onExtensionSettingsClick(item: SourceCatalogItem.Extension)
	fun onExtensionItemClick(item: SourceCatalogItem.Extension)
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
		TooltipCompat.setTooltipText(binding.imageViewAdd, context.getString(item.action.titleRes))
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

