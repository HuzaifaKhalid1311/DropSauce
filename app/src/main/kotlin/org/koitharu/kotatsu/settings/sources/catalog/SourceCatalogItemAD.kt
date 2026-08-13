package org.koitharu.kotatsu.settings.sources.catalog

import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.appcompat.widget.TooltipCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.shape.CornerFamily
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.parser.favicon.extensionPackageFaviconUri
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
	fun onExtensionHideClick(item: SourceCatalogItem.Extension)
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
	binding.imageViewHide.setOnClickListener {
		listener.onExtensionHideClick(item)
	}
	binding.root.setOnClickListener {
		listener.onExtensionItemClick(item)
	}
	val basePadding = context.getThemeDimensionPixelOffset(
		appcompatR.attr.listPreferredItemPaddingEnd,
		binding.root.paddingStart,
	)
	val compactEndPadding = (basePadding - context.resources.getDimensionPixelOffset(R.dimen.margin_small)).coerceAtLeast(0)
	val outerCornerSize = context.resources.getDimensionPixelSize(R.dimen.extension_action_button_size) / 2f
	val innerCornerSize = 8f * context.resources.displayMetrics.density

	bind {
		val isInProgress = item.isInProgress
		val isPrivateMode = item.isPrivateMode
		binding.imageViewAdd.isVisible = true
		binding.imageViewAdd.isEnabled = !isInProgress
		binding.imageViewAdd.alpha = if (isInProgress) 0.45f else 1f
		binding.progressIcon.isVisible = isInProgress
		// An enabled sandbox extension is as installed as a system one, so it gets the same
		// settings + hide actions; only the INSTALL/ENABLE rows (nothing there yet) go without.
		val isInstalled = if (isPrivateMode) {
			item.action == SourceCatalogItem.Extension.Action.DISABLE ||
				item.action == SourceCatalogItem.Extension.Action.UPDATE ||
				item.action == SourceCatalogItem.Extension.Action.UNINSTALL
		} else {
			item.action != SourceCatalogItem.Extension.Action.INSTALL
		}
		binding.imageViewSettings.isVisible = isInstalled && item.sourceName != null
		binding.imageViewSettings.isEnabled = !isInProgress
		binding.imageViewSettings.alpha = if (isInProgress) 0.45f else 1f
		binding.imageViewHide.isVisible = isInstalled
		binding.imageViewHide.isEnabled = !isInProgress
		binding.imageViewHide.alpha = if (isInProgress) 0.45f else 1f
		if (isInstalled) {
			val hideButton = binding.imageViewHide
			hideButton.setIconResource(if (item.isHidden) R.drawable.ic_eye_off else R.drawable.ic_eye)
			val hideDescription = context.getString(if (item.isHidden) R.string.unhide else R.string.hide)
			hideButton.contentDescription = hideDescription
			TooltipCompat.setTooltipText(hideButton, hideDescription)
		}
		binding.buttonGroupActions.isVisible = true
		binding.buttonGroupActions.applyConnectedActionShapes(outerCornerSize, innerCornerSize)
		binding.buttonGroupActions.post {
			binding.buttonGroupActions.applyConnectedActionShapes(outerCornerSize, innerCornerSize)
		}
		binding.root.updatePaddingRelative(end = compactEndPadding)
		binding.imageViewAdd.setIconResource(item.action.iconRes)
		val actionDescription = if (isInProgress) {
			context.getString(R.string.in_progress)
		} else {
			context.getString(item.action.titleRes)
		}
		binding.imageViewAdd.contentDescription = actionDescription
		TooltipCompat.setTooltipText(binding.imageViewAdd, actionDescription)
		binding.textViewTitle.text = item.title
		binding.textViewDescription.text = item.subtitle
		binding.textViewDescription.drawableStart = null
		binding.imageViewIcon.applyExternalSourceStyle(true)
		val sourceIconName = item.sourceIconName
		val iconUrl = item.iconUrl
		// An installed APK extension carries its icon inside itself, so read it from there rather
		// than from the repo's icon url: that url needs the network, and survives only as long as
		// the shared thumbnail disk cache doesn't evict it — which is why installed rows dropped to
		// the letter placeholder at random. Novel plugins are not packages (their icon only ever
		// exists as a url), so they keep the url path.
		val isLnPlugin = sourceIconName?.startsWith("LN_") == true
		if (isInstalled && !isLnPlugin) {
			binding.imageViewIcon.setImageFromUrlAsync(
				url = extensionPackageFaviconUri(item.packageName),
				fallbackName = sourceIconName ?: item.packageName,
			)
		} else if (iconUrl != null) {
			binding.imageViewIcon.setImageFromUrlAsync(
				url = iconUrl,
				fallbackName = sourceIconName ?: item.packageName,
			)
		} else if (sourceIconName != null) {
			binding.imageViewIcon.setImageAsync(MangaSource(sourceIconName))
		} else {
			// Cancel any pending async load left over from a recycled row first; otherwise a
			// late-resolving request can clobber this generated favicon with another extension's icon.
			binding.imageViewIcon.disposeImage()
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

private fun android.view.ViewGroup.applyConnectedActionShapes(outerCornerSize: Float, innerCornerSize: Float) {
	val buttons = sequenceOf(0, 1, 2)
		.mapNotNull { getChildAt(it) as? MaterialButton }
		.filter { it.isVisible }
		.toList()
	buttons.forEachIndexed { index, button ->
		button.applyConnectedActionShape(
			isFirst = index == 0,
			isLast = index == buttons.lastIndex,
			outerCornerSize = outerCornerSize,
			innerCornerSize = innerCornerSize,
		)
	}
}

private fun MaterialButton.applyConnectedActionShape(
	isFirst: Boolean,
	isLast: Boolean,
	outerCornerSize: Float,
	innerCornerSize: Float,
) {
	val leftCornerSize = if (isFirst) outerCornerSize else innerCornerSize
	val rightCornerSize = if (isLast) outerCornerSize else innerCornerSize
	shapeAppearanceModel = shapeAppearanceModel.toBuilder()
		.setTopLeftCorner(CornerFamily.ROUNDED, leftCornerSize)
		.setBottomLeftCorner(CornerFamily.ROUNDED, leftCornerSize)
		.setTopRightCorner(CornerFamily.ROUNDED, rightCornerSize)
		.setBottomRightCorner(CornerFamily.ROUNDED, rightCornerSize)
		.build()
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

