package org.koitharu.kotatsu.settings.sources.catalog

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.list.ui.model.ListModel

sealed interface SourceCatalogItem : ListModel {

	data class Extension(
		val packageName: String,
		val title: String,
		val subtitle: String,
		val action: Action,
		val isInProgress: Boolean = false,
		val iconUrl: String? = null,
		val sourceIconName: String? = null,
		val sourceName: String? = null,
		/** Store that supplied this row. Required for deterministic installs and updates. */
		val storeId: String? = null,
		/** True when this extension is hidden from Explore (installed extensions only). */
		val isHidden: Boolean = false,
		/** True when the extension list is in private mode (controls icon layout). */
		val isPrivateMode: Boolean = false,
	) : SourceCatalogItem {

		enum class Action(
			@DrawableRes val iconRes: Int,
			@StringRes val titleRes: Int,
		) {
			INSTALL(R.drawable.ic_download, R.string.install),
			UPDATE(R.drawable.ic_download, R.string.update),
			UNINSTALL(R.drawable.ic_delete, R.string.uninstall),
			ENABLE(R.drawable.ic_add, R.string.enable),
			DISABLE(R.drawable.ic_close, R.string.disable),
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
