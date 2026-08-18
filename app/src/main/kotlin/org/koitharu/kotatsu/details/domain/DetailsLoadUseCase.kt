package org.koitharu.kotatsu.details.domain

import android.text.Html
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.core.text.getSpans
import androidx.core.text.parseAsHtml
import coil3.request.CachePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import org.koitharu.kotatsu.core.model.isLocal
import org.koitharu.kotatsu.core.model.isExternalSource
import org.koitharu.kotatsu.core.model.MangaSource as ResolveMangaSource
import org.koitharu.kotatsu.core.nav.MangaIntent
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.parser.CachingMangaRepository
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.exceptions.UnsupportedSourceException
import org.koitharu.kotatsu.core.ui.model.MangaOverride
import org.koitharu.kotatsu.core.util.ext.sanitize
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.explore.domain.RecoverMangaUseCase
import org.koitharu.kotatsu.local.data.LocalMangaRepository
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import org.koitharu.kotatsu.parsers.exception.NotFoundException
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.recoverNotNull
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.tracker.domain.CheckNewChaptersUseCase
import javax.inject.Inject
import javax.inject.Provider

class DetailsLoadUseCase @Inject constructor(
	private val mangaDataRepository: MangaDataRepository,
	private val localMangaRepository: LocalMangaRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
	private val recoverUseCase: RecoverMangaUseCase,
	private val imageGetter: Html.ImageGetter,
	private val networkState: NetworkState,
	private val mihonExtensionManager: MihonExtensionManager,
	private val checkNewChaptersUseCase: Provider<CheckNewChaptersUseCase>,
) {

	operator fun invoke(intent: MangaIntent, force: Boolean): Flow<MangaDetails> = flow {
		val manga = requireNotNull(mangaDataRepository.resolveIntent(intent, withChapters = true)) {
			"Cannot resolve intent $intent"
		}
		val override = mangaDataRepository.getOverride(manga.id)
		// The downloaded copy has to be attached to the FIRST emission. The reader commits to this
		// snapshot: it inits its chapter list from it and immediately starts fetching pages, so a
		// later emission that adds the local copy arrives after the chapter was already pulled over
		// the network. The index lookup is a single indexed query when nothing is downloaded, so
		// this emission stays instant - only the storage *scan* stays behind it.
		val savedManga = if (manga.isLocal) null else localMangaRepository.findSavedMangaIndexed(manga)
		// The database is the screen's stale-while-revalidate cache. Do not put source work in front
		// of this emission, which previously made an already-known manga look like a cold load.
		emit(
			MangaDetails(
				manga = manga,
				localManga = savedManga,
				override = override,
				description = manga.description?.parseAsHtml(withImages = false),
				isLoaded = false,
			),
		)
		if (manga.isLocal) {
			loadLocal(manga, override, force)
		} else {
			loadRemote(manga, override, force, savedManga)
		}
	}.map { details ->
		// per-manga "merge scanlators": collapse all branches into one so the whole app
		// (chapter list, reader, page picker) treats the manga as a single entity
		if (mangaDataRepository.isScanlatorsMerged(details.id)) {
			details.withMergedBranches()
		} else {
			details
		}
	}.distinctUntilChanged()
		.flowOn(Dispatchers.Default)

	/**
	 * Load local manga + try to load the linked remote one if network is not restricted
	 * Suppress any network errors
	 */
	private suspend fun FlowCollector<MangaDetails>.loadLocal(manga: Manga, override: MangaOverride?, force: Boolean) {
		val skipNetworkLoad = !force && networkState.isOfflineOrRestricted()
		val localDetails = localMangaRepository.getDetails(manga)
		emit(
			MangaDetails(
				manga = localDetails,
				localManga = null,
				override = override,
				description = localDetails.description?.parseAsHtml(withImages = false),
				isLoaded = skipNetworkLoad,
			),
		)
		if (skipNetworkLoad) {
			return
		}
		val remoteManga = localMangaRepository.getRemoteManga(manga)
		if (remoteManga == null) {
			emit(
				MangaDetails(
					manga = localDetails,
					localManga = null,
					override = override,
					description = localDetails.description?.parseAsHtml(withImages = true),
					isLoaded = true,
				),
			)
		} else {
			val remoteDetails = getDetails(remoteManga, force).getOrNull()
			val mangaDetails = MangaDetails(
				manga = remoteDetails ?: remoteManga,
				localManga = LocalManga(localDetails),
				override = override,
				description = (remoteDetails ?: localDetails).description?.parseAsHtml(withImages = true),
				isLoaded = true,
			)
			if (remoteDetails != null) {
				mangaDataRepository.storeManga(
					remoteDetails,
					replaceExisting = true,
					stripAppliedOverride = false,
					detailsFetched = true,
				)
			}
			emit(mangaDetails)
		}
	}

	/**
	 * Load remote manga + saved one if available.
	 * Emits cached data immediately (if available), then refreshes from the extension.
	 * If the extension is missing, emits the best available cached state first so the UI
	 * shows something before the error snackbar appears, then re-throws so the error
	 * can surface normally through withErrorHandling().
	 */
	private suspend fun FlowCollector<MangaDetails>.loadRemote(
		manga: Manga,
		override: MangaOverride?,
		force: Boolean,
		savedManga: LocalManga?,
	) = coroutineScope {
		// Skip the background refresh entirely if details were fetched recently enough
		// (either by opening this screen or by the new-chapters tracker) — the DB copy is fresh.
		if (!force && !manga.chapters.isNullOrEmpty() &&
			System.currentTimeMillis() - mangaDataRepository.getDetailsUpdatedAt(manga.id) < DETAILS_FRESHNESS_MS
		) {
			emit(
				MangaDetails(
					manga = manga,
					localManga = savedManga ?: localMangaRepository.findSavedManga(manga, withDetails = true),
					override = override,
					description = manga.description?.parseAsHtml(withImages = true),
					isLoaded = true,
				),
			)
			return@coroutineScope
		}
		val remoteDeferred = async {
			getDetails(manga, force)
		}
		// Already resolved from the index? Skip the storage scan.
		val localManga = savedManga ?: localMangaRepository.findSavedManga(manga, withDetails = true)
		if (localManga != null && localManga !== savedManga) {
			emit(
				MangaDetails(
					manga = manga,
					localManga = localManga,
					override = override,
					description = localManga.manga.description?.parseAsHtml(withImages = true),
					isLoaded = false,
				),
			)
		}
		val remoteResult = remoteDeferred.await()
		if (remoteResult.isFailure) {
			// Emit a terminal "loaded" state with whatever we have cached so the UI
			// shows the manga's info before the error snackbar appears.
			emit(
				MangaDetails(
					manga = manga,
					localManga = localManga,
					override = override,
					description = (manga.description ?: localManga?.manga?.description)
						?.parseAsHtml(withImages = true),
					isLoaded = true,
				),
			)
		}
		val remoteDetails = remoteResult.getOrThrow()  // re-throws so the caller shows error
		val mangaDetails = MangaDetails(
			manga = remoteDetails,
			localManga = localManga,
			override = override,
			description = (remoteDetails.description
				?: localManga?.manga?.description)?.parseAsHtml(withImages = true),
			isLoaded = true,
		)
		// Commit the refreshed snapshot before exposing it as complete. Otherwise a process restart
		// immediately after the UI updates can cancel this coroutine between emit() and the write,
		// making the successfully loaded details disappear on the next open.
		mangaDataRepository.storeManga(
			remoteDetails,
			replaceExisting = true,
			stripAppliedOverride = false,
			detailsFetched = true,
		)
		emit(mangaDetails)
		// Feed chapters found by this refresh into the tracker so they appear in the updates feed
		// instead of being silently swallowed by the next background check. Emits nothing to the UI
		// and never notifies — the user is already looking at the manga.
		runCatchingCancellable {
			checkNewChaptersUseCase.get().invoke(remoteDetails)
		}.onFailure { e ->
			e.printStackTraceDebug()
		}
	}

	private suspend fun getDetails(seed: Manga, force: Boolean) = runCatchingCancellable {
		loadDetails(seed, force, refreshExtensions = false)
	}.recoverCatching { error ->
		// Only retry with extension refresh for UnsupportedSourceException on Mihon sources.
		// Catching ALL errors from MIHON sources would hide network errors and cause
		// infinite-retry behaviour on permanent failures.
		if (error is UnsupportedSourceException && seed.source.isExternalSource()) {
			loadDetails(seed, force, refreshExtensions = true)
		} else {
			throw error
		}
	}.recoverNotNull { e ->
		if (e is NotFoundException) {
			recoverUseCase(seed)
		} else {
			null
		}
	}

	private suspend fun loadDetails(seed: Manga, force: Boolean, refreshExtensions: Boolean): Manga {
		val resolvedSeed = if (seed.source.name.startsWith("MIHON_")) {
			mihonExtensionManager.ensureReady(forceRefresh = refreshExtensions || seed.source !is MihonMangaSource)
			val resolvedSource = ResolveMangaSource(seed.source.name)
			seed.copy(source = resolvedSource)
		} else {
			seed
		}
		val repository = mangaRepositoryFactory.create(resolvedSeed.source)
		return if (repository is CachingMangaRepository) {
			// Reuse a result already refreshed during this app session. A process restart clears the
			// memory cache, so the first open still revalidates in the background; an explicit
			// pull-to-refresh always bypasses it.
			repository.getDetails(
				resolvedSeed,
				if (force) CachePolicy.WRITE_ONLY else CachePolicy.ENABLED,
			)
		} else {
			repository.getDetails(resolvedSeed)
		}
	}

	private suspend fun String.parseAsHtml(withImages: Boolean): CharSequence? {
		// Many sources deliver plain-text descriptions using literal newlines for paragraphs and
		// " - " for lists. Html.fromHtml collapses that whitespace into single spaces, producing one
		// run-on blob. Promote line breaks to <br> unless the text already uses block tags, so real
		// HTML descriptions are untouched. ponytail: cheap heuristic, mirrors Mihon's eol-as-newline.
		val html = if (contains("<br", ignoreCase = true) || contains("<p", ignoreCase = true)) {
			this
		} else {
			replace("\n", "<br>")
		}
		return if (withImages) {
			runInterruptible(Dispatchers.IO) {
				html.parseAsHtml(imageGetter = imageGetter)
			}.filterSpans()
		} else {
			runInterruptible(Dispatchers.Default) {
				html.parseAsHtml()
			}.filterSpans().sanitize()
		}.trim().nullIfEmpty()
	}

	private companion object {
		// Don't auto-refresh details more often than this; pull-to-refresh always bypasses
		val DETAILS_FRESHNESS_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(12)
	}

	private fun Spanned.filterSpans(): Spanned {
		val spannable = SpannableString.valueOf(this)
		val spans = spannable.getSpans<ForegroundColorSpan>()
		for (span in spans) {
			spannable.removeSpan(span)
		}
		return spannable
	}
}
