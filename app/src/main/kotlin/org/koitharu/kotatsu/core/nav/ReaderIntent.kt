package org.haziffe.dropsauce.core.nav

import android.content.Context
import android.content.Intent
import org.haziffe.dropsauce.BuildConfig
import org.haziffe.dropsauce.bookmarks.domain.Bookmark
import org.haziffe.dropsauce.core.model.parcelable.ParcelableManga
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.reader.ui.ReaderActivity
import org.haziffe.dropsauce.reader.ui.ReaderState

@JvmInline
value class ReaderIntent private constructor(
	val intent: Intent,
) {

	class Builder(context: Context) {

		private val intent = Intent(context, ReaderActivity::class.java)
			.setAction(ACTION_MANGA_READ)

		fun manga(manga: Manga) = apply {
			intent.putExtra(AppRouter.KEY_MANGA, ParcelableManga(manga))
			intent.setData(AppRouter.shortMangaUrl(manga.id))
		}

		fun mangaId(mangaId: Long) = apply {
			intent.putExtra(AppRouter.KEY_ID, mangaId)
			intent.setData(AppRouter.shortMangaUrl(mangaId))
		}

		fun incognito() = apply {
			intent.putExtra(EXTRA_INCOGNITO, true)
		}

		fun branch(branch: String?) = apply {
			intent.putExtra(EXTRA_BRANCH, branch)
		}

		fun state(state: ReaderState?) = apply {
			intent.putExtra(EXTRA_STATE, state)
		}

		fun bookmark(bookmark: Bookmark) = manga(
			bookmark.manga,
		).state(
			ReaderState(
				chapterId = bookmark.chapterId,
				page = bookmark.page,
				scroll = bookmark.scroll,
			),
		)

		fun build() = ReaderIntent(intent)
	}

	companion object {
		const val ACTION_MANGA_READ = "${BuildConfig.APPLICATION_ID}.action.READ_MANGA"
		const val EXTRA_STATE = "state"
		const val EXTRA_BRANCH = "branch"
		const val EXTRA_INCOGNITO = "incognito"
	}
}
