package org.koitharu.kotatsu.suggestions.domain

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toEntities
import org.koitharu.kotatsu.core.db.entity.toEntity
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.db.entity.toMangaTags
import org.koitharu.kotatsu.core.db.entity.toMangaTagsList
import org.koitharu.kotatsu.core.model.toMangaSources
import org.koitharu.kotatsu.core.ui.util.ReversibleHandle
import org.koitharu.kotatsu.core.util.ext.mapItems
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.suggestions.data.SuggestionEntity
import org.koitharu.kotatsu.suggestions.data.SuggestionWithManga
import javax.inject.Inject

class SuggestionRepository @Inject constructor(
	private val db: MangaDatabase,
	@ApplicationContext context: Context,
) {

	/** Ids of titles hidden from suggestions for good. */
	private val hiddenPrefs = context.getSharedPreferences("suggestions_hidden", Context.MODE_PRIVATE)

	fun observeAll(): Flow<List<Manga>> {
		return db.getSuggestionDao().observeAll().mapItems {
			it.toManga()
		}
	}

	fun observeAll(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<Manga>> {
		return db.getSuggestionDao().observeAll(limit, filterOptions).mapItems {
			it.toManga()
		}
	}

	/**
	 * Emits a fresh random subset whenever the stored suggestions change. Re-shuffles only when
	 * membership changes, not on unrelated row updates. When titles are only removed ("Not interested"),
	 * the rest keep their place and a new one fills the gap, so the carousel doesn't reshuffle; undoing
	 * that brings the previous titles back in their old order.
	 */
	fun observeRandomList(limit: Int): Flow<List<Manga>> = flow {
		var allIds: Set<Long>? = null
		var shown = emptyList<Manga>()
		// What was stored and shown right before the last removal, to restore it on undo.
		var beforeRemoval: Pair<Set<Long>, List<Manga>>? = null
		observeAll().collect { all ->
			val ids = all.mapTo(HashSet(all.size)) { it.id }
			if (ids == allIds) return@collect
			val previousIds = allIds
			val byId = all.associateBy { it.id }
			val undone = beforeRemoval?.takeIf { it.first == ids }?.second
			beforeRemoval = null
			shown = if (undone != null) {
				undone.mapNotNull { byId[it.id] }
			} else if (previousIds != null && shown.isNotEmpty() && previousIds.containsAll(ids)) {
				beforeRemoval = previousIds to shown
				val kept = shown.mapNotNull { byId[it.id] }
				val keptIds = kept.mapTo(HashSet()) { it.id }
				kept + all.filterNot { it.id in keptIds }.shuffled().take(limit - kept.size)
			} else {
				all.shuffled().take(limit)
			}
			allIds = ids
			emit(shown)
		}
	}

	/** Never suggests [manga] again. Undoing puts it back where it was. */
	suspend fun hide(manga: Manga): ReversibleHandle {
		val dao = db.getSuggestionDao()
		val entity = dao.find(manga.id)
		setHidden(manga.id, true)
		dao.delete(manga.id)
		return ReversibleHandle {
			setHidden(manga.id, false)
			if (entity != null) dao.upsert(entity)
		}
	}

	@Synchronized
	private fun setHidden(mangaId: Long, isHidden: Boolean) {
		val ids = hiddenPrefs.getStringSet(KEY_HIDDEN_IDS, null).orEmpty().toMutableSet()
		if (isHidden) ids.add(mangaId.toString()) else ids.remove(mangaId.toString())
		hiddenPrefs.edit { putStringSet(KEY_HIDDEN_IDS, ids) }
	}

	suspend fun clear() {
		db.getSuggestionDao().deleteAll()
	}

	suspend fun getTopTags(limit: Int): List<MangaTag> {
		return db.getSuggestionDao().getTopTags(limit)
			.toMangaTagsList()
	}

	suspend fun getTopSources(limit: Int): List<MangaSource> {
		return db.getSuggestionDao().getTopSources(limit)
			.toMangaSources()
	}

	/** Stores [suggestions] minus the hidden titles, and returns what was stored. */
	suspend fun replace(suggestions: List<MangaSuggestion>): List<MangaSuggestion> {
		val hiddenIds = hiddenPrefs.getStringSet(KEY_HIDDEN_IDS, null).orEmpty()
		val kept = suggestions.filterNot { it.manga.id.toString() in hiddenIds }
		db.withTransaction {
			db.getSuggestionDao().deleteAll()
			kept.forEach { (manga, relevance) ->
				val tags = manga.tags.toEntities()
				db.getTagsDao().upsert(tags)
				db.getMangaDao().upsert(manga.toEntity(), tags)
				db.getSuggestionDao().upsert(
					SuggestionEntity(
						mangaId = manga.id,
						relevance = relevance,
						createdAt = System.currentTimeMillis(),
					),
				)
			}
		}
		return kept
	}

	private fun SuggestionWithManga.toManga() = manga.toManga(tags.toMangaTags(), null)

	private companion object {

		const val KEY_HIDDEN_IDS = "ids"
	}
}
