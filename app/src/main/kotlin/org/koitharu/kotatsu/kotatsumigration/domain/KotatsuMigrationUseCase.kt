package org.koitharu.kotatsu.kotatsumigration.domain

import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.model.MissingMangaSource
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.kotatsumigration.data.KotatsuSourceMap
import org.koitharu.kotatsu.kotatsumigration.data.MihonTarget
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.toSManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import javax.inject.Inject

/**
 * Drives migration of restored Kotatsu library entries (built-in sources) onto installed Mihon
 * extensions. [scan] finds candidates; [migrate] resolves the predefined mapping and re-keys the
 * entry onto the mapped Mihon source **offline** via [KotatsuMangaMigrator] (no per-manga network).
 * Live chapter lists load lazily when the user opens each manga.
 */
class KotatsuMigrationUseCase @Inject constructor(
	private val database: MangaDatabase,
	private val sourceMap: KotatsuSourceMap,
	private val mihonExtensionManager: MihonExtensionManager,
	private val mangaDataRepository: MangaDataRepository,
	private val migrator: KotatsuMangaMigrator,
) {

	/** Loads installed extensions so [migrate] can resolve sources. Call once before a batch. */
	suspend fun prepare() {
		mihonExtensionManager.ensureReady()
	}

	/**
	 * Restored manga on a built-in (non-Mihon) source that still carry user data, plus entries an
	 * earlier build already migrated but left on an unusable absolute url (see
	 * [MangaDao.findMigratedMangaWithAbsoluteUrl][org.koitharu.kotatsu.core.db.dao.MangaDao.findMigratedMangaWithAbsoluteUrl]).
	 */
	suspend fun scan(): List<LegacyManga> {
		val dao = database.getMangaDao()
		return (dao.findLegacyMangaWithUserData() + dao.findMigratedMangaWithAbsoluteUrl()).map {
			LegacyManga(id = it.manga.id, sourceName = it.manga.source)
		}
	}

	suspend fun migrate(legacy: LegacyManga): Outcome {
		if (legacy.sourceName.startsWith("MIHON_")) {
			return repairAbsoluteUrl(legacy)
		}
		val target = sourceMap.resolve(legacy.sourceName) ?: return Outcome.NoMapping
		// Convert regardless of whether the extension is installed: if it is, link to the live
		// source; if not, store a MissingMangaSource carrying the display name so the entry still
		// shows in the library and is recommended for install. Either way the new id is identical
		// (a pure hash of source name + url), so installing the extension later "lights it up".
		val installed = mihonExtensionManager.getMihonMangaSourceById(target.sourceId)
		val newSource: MangaSource = installed
			?: MissingMangaSource("MIHON_${target.sourceId}", target.sourceName)
		val oldManga = mangaDataRepository.findMangaById(legacy.id, withChapters = true)
			?: return Outcome.Failed("Manga ${legacy.id} not found")
		return try {
			migrator(oldManga, newSource)
			if (installed != null) Outcome.Migrated else Outcome.ConvertedPendingExtension(target)
		} catch (e: Exception) {
			Outcome.Failed(e.message)
		}
	}

	/**
	 * Re-keys an already-migrated entry off its absolute url and onto the relative one its extension
	 * expects — same source, same user data, corrected id.
	 *
	 * Repaired only when the extension is installed and [isUrlBroken] proves the url can't be fetched
	 * as stored; a source that genuinely owns absolute urls is left untouched.
	 */
	private suspend fun repairAbsoluteUrl(legacy: LegacyManga): Outcome {
		val sourceId = legacy.sourceName.removePrefix("MIHON_").substringBefore(':').toLongOrNull()
			?: return Outcome.NoMapping
		val source = mihonExtensionManager.getMihonMangaSourceById(sourceId)
			?: return Outcome.Failed("Extension for ${legacy.sourceName} not installed")
		val httpSource = source.catalogueSource as? HttpSource
			?: return Outcome.Failed("${legacy.sourceName} is not an HttpSource")
		val oldManga = mangaDataRepository.findMangaById(legacy.id, withChapters = true)
			?: return Outcome.Failed("Manga ${legacy.id} not found")
		if (!httpSource.isUrlBroken(oldManga)) {
			return Outcome.Failed("${source.displayName} owns its absolute urls, skipped ${oldManga.title}")
		}
		return try {
			if (migrator(oldManga, source) != null) Outcome.Migrated else Outcome.NoMapping
		} catch (e: Exception) {
			Outcome.Failed(e.message)
		}
	}

	/**
	 * True when [manga]'s stored (absolute) url can't survive this source's request building — i.e.
	 * the source concatenates `baseUrl + url` and so glues a whole second url onto its own. A url too
	 * malformed to build a request from at all is broken by definition.
	 */
	private fun HttpSource.isUrlBroken(manga: Manga): Boolean {
		val baseHost = baseUrl.toHttpUrlOrNull()?.host ?: return false
		val requestUrl = runCatchingCancellable {
			mangaDetailsRequest(manga.toSManga()).url
		}.getOrElse { return true }
		return isGluedUrl(baseHost, requestUrl.host, requestUrl.encodedPath)
	}

	data class LegacyManga(
		val id: Long,
		val sourceName: String,
	)

	sealed interface Outcome {
		/** Converted and the matching extension is installed — works immediately. */
		data object Migrated : Outcome

		/** Converted, but the matching extension isn't installed yet — recommend installing it. */
		data class ConvertedPendingExtension(val target: MihonTarget) : Outcome

		/** No predefined Mihon equivalent for this Kotatsu source. */
		data object NoMapping : Outcome

		/** Re-keying threw. */
		data class Failed(val message: String?) : Outcome
	}
}
