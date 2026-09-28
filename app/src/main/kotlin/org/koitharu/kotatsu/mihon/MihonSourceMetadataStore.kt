package org.koitharu.kotatsu.mihon

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Kotatsu's Manga model has no fields for Mihon's memo/update strategy/initialized state.
 * Persist them out-of-band so recreating a repository/source does not silently erase extension
 * state that Mihon stores in its manga table.
 */
internal class MihonSourceMetadataStore(context: Context) {

	private val preferences = context.getSharedPreferences(STORAGE_NAME, Context.MODE_PRIVATE)
	private val json = Json

	fun restore(sourceId: Long, mangaUrl: String, manga: SManga) {
		val prefix = keyPrefix(sourceId, mangaUrl)
		preferences.getString(prefix + KEY_MEMO, null)?.let { encoded ->
			runCatching { json.parseToJsonElement(encoded).jsonObject }
				.getOrNull()
				?.let { manga.memo = it }
		}
		preferences.getString(prefix + KEY_UPDATE_STRATEGY, null)
			?.let { runCatching { UpdateStrategy.valueOf(it) }.getOrNull() }
			?.let { manga.update_strategy = it }
		if (preferences.contains(prefix + KEY_INITIALIZED)) {
			manga.initialized = preferences.getBoolean(prefix + KEY_INITIALIZED, manga.initialized)
		}
	}

	/**
	 * Persist only the memos of a listing page (used at browse/search time). New-API extensions carry
	 * the manga id in SManga.memo and need it back inside getMangaUpdate; a full [save] here would
	 * also overwrite update_strategy/initialized with pre-details defaults.
	 *
	 * Every edit rewrites the whole (ever-growing) prefs file, so write once per page and only what
	 * changed - one edit per list item used to queue a full rewrite for each of them.
	 */
	fun saveMemos(sourceId: Long, mangas: List<SManga>) {
		val changed = mangas.mapNotNull { manga ->
			if (manga.memo.isEmpty()) return@mapNotNull null
			val key = keyPrefix(sourceId, manga.url) + KEY_MEMO
			val value = manga.memo.toString()
			if (preferences.getString(key, null) == value) null else key to value
		}
		if (changed.isEmpty()) return
		preferences.edit {
			for ((key, value) in changed) putString(key, value)
		}
	}

	fun save(sourceId: Long, mangaUrl: String, manga: SManga) {
		val prefix = keyPrefix(sourceId, mangaUrl)
		val memo = manga.memo.toString()
		val strategy = manga.update_strategy.name
		// Runs on every details load; skip the full-file rewrite when nothing changed.
		if (preferences.getString(prefix + KEY_MEMO, null) == memo &&
			preferences.getString(prefix + KEY_UPDATE_STRATEGY, null) == strategy &&
			preferences.contains(prefix + KEY_INITIALIZED) &&
			preferences.getBoolean(prefix + KEY_INITIALIZED, false) == manga.initialized
		) {
			return
		}
		preferences.edit {
			putString(prefix + KEY_MEMO, memo)
			putString(prefix + KEY_UPDATE_STRATEGY, strategy)
			putBoolean(prefix + KEY_INITIALIZED, manga.initialized)
		}
	}

	/**
	 * New-API extensions (KeiSource/Iken) store the chapter id in SChapter.memo and throw
	 * "Refresh Chapter List" in getPageList when it is missing. Kotatsu's chapter model cannot
	 * carry it, so persist it per (source, chapter url) like the manga sidecar above.
	 */
	fun restoreChapterMemo(sourceId: Long, chapterUrl: String): JsonObject? =
		preferences.getString(keyPrefix(sourceId, chapterUrl) + KEY_MEMO, null)?.let { encoded ->
			runCatching { json.parseToJsonElement(encoded).jsonObject }.getOrNull()
		}

	// ponytail: one prefs entry per chapter, unbounded growth; move to a Room table if the
	// prefs file ever gets noticeably large.
	fun saveChapterMemos(sourceId: Long, memos: Map<String, JsonObject>) {
		val changed = memos.mapNotNull { (chapterUrl, memo) ->
			val key = keyPrefix(sourceId, chapterUrl) + KEY_MEMO
			val value = memo.toString()
			if (preferences.getString(key, null) == value) null else key to value
		}
		if (changed.isEmpty()) return
		preferences.edit {
			for ((key, value) in changed) putString(key, value)
		}
	}

	private fun keyPrefix(sourceId: Long, mangaUrl: String): String =
		Hash.sha256("$sourceId\n$mangaUrl") + "."

	private companion object {
		const val STORAGE_NAME = "mihon_source_metadata"
		const val KEY_MEMO = "memo"
		const val KEY_UPDATE_STRATEGY = "update_strategy"
		const val KEY_INITIALIZED = "initialized"
	}
}
