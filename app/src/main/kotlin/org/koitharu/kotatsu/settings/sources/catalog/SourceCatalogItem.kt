package org.haziffe.dropsauce.settings.sources.catalog

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.parsers.model.MangaSource

sealed interface SourceCatalogItem : ListModel {

	data class Source(
		val source: MangaSource,
		val isAddAvailable: Boolean,
	) : SourceCatalogItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Source && other.source == source
		}
	}

	data class Extension(
		val packageName: String,
		val title: String,
		val subtitle: String,
		val action: Action,
		val iconUrl: String? = null,
		val sourceIconName: String? = null,
		val sourceName: String? = null,
	) : SourceCatalogItem {

		enum class Action(
			@DrawableRes val iconRes: Int,
			@StringRes val titleRes: Int,
		) {
			INSTALL(R.drawable.ic_download, R.string.install),
			UPDATE(R.drawable.ic_download, R.string.update),
			UNINSTALL(R.drawable.ic_delete, R.string.uninstall),
		}

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Extension &&
				other.packageName == packageName &&
				other.action == action
		}
	}

	data class Hint(
		@DrawableRes val icon: Int,
		@StringRes val title: Int,
		@StringRes val text: Int,
	) : SourceCatalogItem {

		override fun areItemsTheSame(other: ListModel): Boolean {
			return other is Hint && other.title == title
		}
	}
}
