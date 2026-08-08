package org.koitharu.kotatsu.list.domain

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.model.LocalMangaSource
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.model.getStoredTitleOrNull
import org.koitharu.kotatsu.core.model.unwrap
import org.koitharu.kotatsu.core.model.iconResId
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.parser.favicon.faviconUri
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.MangaTag

sealed interface ListFilterOption {

	@get:StringRes
	val titleResId: Int

	@get:DrawableRes
	val iconResId: Int

	val titleText: CharSequence?

	val groupKey: String

	fun getIconData(): Any? = null

	data object Downloaded : ListFilterOption {

		override val titleResId: Int
			get() = R.string.on_device

		override val iconResId: Int
			get() = R.drawable.ic_storage

		override val titleText: CharSequence?
			get() = null

		override val groupKey: String
			get() = "_downloaded"
	}

	enum class Macro(
		@StringRes override val titleResId: Int,
		@DrawableRes override val iconResId: Int,
	) : ListFilterOption {

		COMPLETED(R.string.status_completed, R.drawable.ic_state_finished),
		NEW_CHAPTERS(R.string.new_chapters, R.drawable.ic_updated),
		FAVORITE(R.string.favourites, R.drawable.ic_heart_outline),
		NSFW(R.string.nsfw, R.drawable.ic_nsfw),
		;

		override val titleText: CharSequence?
			get() = null

		override val groupKey: String
			get() = name
	}

	data class Branch(
		override val titleText: String?,
		val chaptersCount: Int,
	) : ListFilterOption {

		override val titleResId: Int
			get() = if (titleText == null) R.string.system_default else 0

		override val iconResId: Int
			get() = R.drawable.ic_language

		override val groupKey: String
			get() = "_branch"
	}

	/**
	 * Publication status as reported by the source. Shown as a single chip that cycles through
	 * [CYCLE] on each tap, so [state] is null only for the "nothing picked yet" chip — an option
	 * with a null state is never applied and never reaches SQL.
	 */
	data class State(
		val state: MangaState?,
	) : ListFilterOption {

		override val titleResId: Int
			get() = state?.titleResId ?: R.string.status

		override val iconResId: Int
			get() = state?.iconResId ?: R.drawable.ic_status

		override val titleText: CharSequence?
			get() = null

		override val groupKey: String
			get() = "_state"

		companion object {

			val CYCLE = listOf(
				MangaState.ONGOING,
				MangaState.FINISHED,
				MangaState.PAUSED,
			)
		}
	}

	data class Tag(
		val tag: MangaTag
	) : ListFilterOption {

		val tagId: Long = tag.toEntity().id

		override val titleResId: Int
			get() = 0

		override val iconResId: Int
			get() = R.drawable.ic_tag

		override val titleText: String
			get() = tag.title

		override val groupKey: String
			get() = "_tag"
	}

	data class Favorite(
		val category: FavouriteCategory
	) : ListFilterOption {

		override val titleResId: Int
			get() = 0

		override val iconResId: Int
			get() = R.drawable.ic_heart_outline

		override val titleText: String
			get() = category.title

		override val groupKey: String
			get() = "_favcat"
	}

	data class Source(
		val mangaSource: MangaSource
	) : ListFilterOption {
		override val titleResId: Int
			get() = when (mangaSource.unwrap()) {
				LocalMangaSource -> R.string.local_storage
				else -> 0
			}

		override val iconResId: Int
			get() = R.drawable.ic_web

		override val titleText: CharSequence?
			get() = mangaSource.getStoredTitleOrNull() ?: when (val source = mangaSource.unwrap()) {
				// For missing Mihon sources where the display name isn't yet known, show
				// only the favicon icon rather than the raw "MIHON_<id>" internal name.
				is MissingMangaSource -> if (source.name.startsWith("MIHON_")) null else source.name.ifBlank { null }
				else -> null
			}

		override val groupKey: String
			get() = "_source"

		override fun getIconData() = mangaSource.faviconUri()
	}

	data class Inverted(
		val option: ListFilterOption,
		override val iconResId: Int,
		override val titleResId: Int,
		override val titleText: CharSequence?,
	) : ListFilterOption {

		override val groupKey: String
			get() = "_inv" + option.groupKey
	}

	companion object {

		val SFW
			get() = Inverted(
				option = Macro.NSFW,
				iconResId = R.drawable.ic_sfw,
				titleResId = R.string.sfw,
				titleText = null,
			)

		val NOT_FAVORITE
			get() = Inverted(
				option = Macro.FAVORITE,
				iconResId = R.drawable.ic_heart_off,
				titleResId = R.string.not_in_favorites,
				titleText = null,
			)
	}
}
