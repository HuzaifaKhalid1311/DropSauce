package org.koitharu.kotatsu.details.ui.pager

import android.app.Activity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus
import okio.FileNotFoundException
import org.koitharu.kotatsu.bookmarks.domain.BookmarksRepository
import org.koitharu.kotatsu.core.model.toChipModel
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsStateFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.AlphanumComparator
import org.koitharu.kotatsu.core.util.LocaleStringComparator
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.combine
import org.koitharu.kotatsu.core.util.ext.requireValue
import org.koitharu.kotatsu.core.util.ext.sortedWithSafe
import org.koitharu.kotatsu.details.data.MangaDetails
import org.koitharu.kotatsu.details.domain.DetailsInteractor
import org.koitharu.kotatsu.details.ui.DetailsExpressiveActivity
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.details.ui.mapChapters
import org.koitharu.kotatsu.details.ui.model.ChapterListItem
import org.koitharu.kotatsu.download.ui.worker.DownloadTask
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListFilterOption
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.local.domain.model.LocalManga
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.reader.ui.ReaderActivity
import org.koitharu.kotatsu.reader.ui.ReaderState
import org.koitharu.kotatsu.reader.ui.ReaderViewModel

abstract class ChaptersPagesViewModel(
	@JvmField protected val settings: AppSettings,
	@JvmField protected val interactor: DetailsInteractor,
	private val bookmarksRepository: BookmarksRepository,
	private val historyRepository: HistoryRepository,
	private val downloadScheduler: DownloadWorker.Scheduler,
	private val deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
	private val localStorageChanges: SharedFlow<LocalManga?>,
	private val mangaDataRepository: MangaDataRepository,
	private val mangaRepositoryFactory: MangaRepository.Factory,
) : BaseViewModel() {

	val mangaDetails = MutableStateFlow<MangaDetails?>(null)
	val readingState = MutableStateFlow<ReaderState?>(null)

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onDownloadStarted = MutableEventFlow<Unit>()
	val onMangaRemoved = MutableEventFlow<Manga>()
	val onOpenChapterInBrowser = MutableEventFlow<String>()

	val chaptersQuery = MutableStateFlow("")
	val selectedBranch = MutableStateFlow<String?>(null)

	val manga = mangaDetails.map { x -> x?.toManga() }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val coverUrl = mangaDetails.map { x -> x?.coverUrl(preferLarge = !settings.isBackdropEnabled) }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val backdropUrl = mangaDetails.map { x -> x?.backdropUrl }
		.withErrorHandling()
		.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, null)

	val isChaptersReversed = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_REVERSE_CHAPTERS,
		valueProducer = { isChaptersReverse },
	)

	val isChaptersInGridView = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_VIEW_CHAPTERS,
		valueProducer = { isChaptersGridView },
	)

	val isDownloadedOnly = MutableStateFlow(false)

	/** Some sources hand back an unordered chapter list; this re-sorts it by title for this manga only. */
	val isChaptersSortedByName = MutableStateFlow(false)

	val newChaptersCount = mangaDetails.flatMapLatest { d ->
		if (d?.isLocal == false) {
			interactor.observeNewChapters(d.id)
		} else {
			flowOf(0)
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, 0)

	val emptyReason: StateFlow<EmptyMangaReason?> = combine(
		mangaDetails,
		isLoading,
		onError.onStart { emit(null) },
	) { details, loading, error ->
		when {
			details == null || loading -> null
			details.chapters.isNotEmpty() -> null
			details.toManga().state == MangaState.RESTRICTED -> EmptyMangaReason.RESTRICTED
			error != null -> EmptyMangaReason.LOADING_ERROR
			else -> EmptyMangaReason.NO_CHAPTERS
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.WhileSubscribed(), null)

	val bookmarks = mangaDetails.flatMapLatest {
		if (it != null) {
			bookmarksRepository.observeBookmarks(it.toManga()).withErrorHandling()
		} else {
			flowOf(emptyList())
		}
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Lazily, emptyList())

	val chapters = combine(
		combine(
			mangaDetails,
			readingState.map { it?.chapterId ?: 0L }.distinctUntilChanged(),
			selectedBranch,
			newChaptersCount,
			bookmarks,
			isChaptersInGridView,
			isDownloadedOnly,
		) { manga, currentChapterId, branch, news, bookmarks, grid, downloadedOnly ->
			manga?.mapChapters(
				currentChapterId = currentChapterId,
				newCount = news,
				branch = branch,
				bookmarks = bookmarks,
				isGrid = grid,
				isDownloadedOnly = downloadedOnly,
			).orEmpty()
		},
		isChaptersReversed,
		chaptersQuery,
		isChaptersSortedByName,
	) { list, reversed, query, sortByName ->
		val sorted = if (sortByName) list.sortedWithSafe(CHAPTER_NAME_COMPARATOR) else list
		(if (reversed) sorted.asReversed() else sorted).filterSearch(query)
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, emptyList())

	val quickFilter = combine(
		mangaDetails,
		selectedBranch,
	) { details, branch ->
		val branches = details?.chapters?.toList()?.sortedWithSafe(
			compareBy(LocaleStringComparator()) { it.first },
		).orEmpty()
		if (branches.size > 1) {
			branches.map {
				val option = ListFilterOption.Branch(titleText = it.first, chaptersCount = it.second.size)
				option.toChipModel(isChecked = it.first == branch)
			}
		} else {
			emptyList()
		}
	}

	val isScanlatorsMerged = MutableStateFlow(false)

	init {
		launchJob(Dispatchers.Default) {
			localStorageChanges
				.collect { onDownloadComplete(it) }
		}
		launchJob(Dispatchers.Default) {
			val id = mangaDetails.filterNotNull().first().id
			isScanlatorsMerged.value = mangaDataRepository.isScanlatorsMerged(id)
			isChaptersSortedByName.value = settings.isChaptersSortedByName(id)
		}
	}

	abstract fun reload()

	fun setScanlatorsMerged(isMerged: Boolean) {
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.requireValue().sourceManga
			mangaDataRepository.setScanlatorsMerged(manga, isMerged)
			isScanlatorsMerged.value = isMerged
			selectedBranch.value = null
			reload()
		}
	}

	fun setChaptersSortedByName(value: Boolean) {
		val id = mangaDetails.value?.id ?: return
		settings.setChaptersSortedByName(id, value)
		isChaptersSortedByName.value = value
	}

	fun setSelectedBranch(branch: String?) {
		selectedBranch.value = branch
	}

	fun performChapterSearch(query: String?) {
		chaptersQuery.value = query?.trim().orEmpty()
	}

	fun getMangaOrNull(): Manga? = mangaDetails.value?.toManga()

	fun getSourceMangaOrNull(): Manga? = mangaDetails.value?.sourceManga

	fun requireManga() = mangaDetails.requireValue().toManga()

	/**
	 * How a chapter tap should be handled relative to the saved reading progress:
	 * [ChapterOpenMode.NORMAL] — open as usual (progress moves along),
	 * [ChapterOpenMode.ASK] — the user is jumping away from their progress, ask peek vs move.
	 */
	open suspend fun getChapterOpenMode(chapterId: Long): ChapterOpenMode {
		if (!settings.isChapterJumpDialogEnabled) {
			return ChapterOpenMode.NORMAL // user opted out — every open moves progress
		}
		val details = mangaDetails.value ?: return ChapterOpenMode.NORMAL
		val manga = details.toManga()
		val history = runCatchingCancellable {
			historyRepository.getOne(manga)
		}.getOrNull() ?: return ChapterOpenMode.NORMAL // first open — no progress to protect
		if (chapterId == history.chapterId) {
			return ChapterOpenMode.NORMAL // resuming the current chapter
		}
		if (interactor.observeIncognitoMode(flowOf(manga)).first() == TriStateOption.ENABLED) {
			return ChapterOpenMode.NORMAL // nothing will be saved anyway
		}
		val chapters = details.chapters.values.firstOrNull { list -> list.any { it.id == chapterId } }
			?: return ChapterOpenMode.NORMAL
		val currentIndex = chapters.indexOfFirst { it.id == history.chapterId }
		val targetIndex = chapters.indexOfFirst { it.id == chapterId }
		return if (currentIndex >= 0 && targetIndex == currentIndex + 1) {
			ChapterOpenMode.NORMAL // the next chapter — normal reading flow
		} else {
			ChapterOpenMode.ASK
		}
	}

	fun disableChapterJumpDialog() {
		settings.isChapterJumpDialogEnabled = false
	}

	fun markChapterAsCurrent(chapterId: Long) {
		launchJob(Dispatchers.Default) {
			val manga = mangaDetails.requireValue()
			val chapters = checkNotNull(manga.chapters[selectedBranch.value])
			val chapterIndex = chapters.indexOfFirst { it.id == chapterId }
			check(chapterIndex in chapters.indices) { "Chapter not found" }
			val percent = (chapterIndex + 1) / chapters.size.toFloat()
			historyRepository.addOrUpdate(
				manga = manga.toManga(),
				chapterId = chapterId,
				page = 0,
				scroll = 0,
				percent = percent,
				force = true,
			)
		}
	}

	fun openChapterInBrowser(chapterId: Long) {
		val chapter = chapters.value.firstOrNull { it.chapter.id == chapterId }?.chapter ?: return
		launchJob(Dispatchers.Default) {
			val url = mangaRepositoryFactory.create(requireManga().source).getChapterUrl(chapter)
			if (!url.isNullOrEmpty()) {
				onOpenChapterInBrowser.call(url)
			}
		}
	}

	fun download(chaptersIds: Set<Long>?, allowMeteredNetwork: Boolean) {
		launchJob(Dispatchers.Default) {
			val manga = requireManga()
			val task = DownloadTask(
				mangaId = manga.id,
				isPaused = false,
				isSilent = false,
				chaptersIds = chaptersIds?.toLongArray(),
				destination = null,
				format = null,
				allowMeteredNetwork = allowMeteredNetwork,
			)
			downloadScheduler.schedule(setOf(manga to task))
			onDownloadStarted.call(Unit)
		}
	}

	fun deleteLocal() {
		val m = mangaDetails.value?.local?.manga
		if (m == null) {
			errorEvent.call(FileNotFoundException())
			return
		}
		launchLoadingJob(Dispatchers.Default) {
			deleteLocalMangaUseCase(m)
			onMangaRemoved.call(m)
		}
	}

	private fun List<ChapterListItem>.filterSearch(query: String): List<ChapterListItem> {
		if (query.isEmpty() || this.isEmpty()) {
			return this
		}
		return filter { it.contains(query) }
	}

	private suspend fun onDownloadComplete(downloadedManga: LocalManga?) {
		downloadedManga ?: return
		mangaDetails.update {
			interactor.updateLocal(it, downloadedManga)
		}
	}

	class ActivityVMLazy(
		private val fragment: Fragment,
	) : Lazy<ChaptersPagesViewModel> {
		private var cached: ChaptersPagesViewModel? = null

		override val value: ChaptersPagesViewModel
			get() {
				val viewModel = cached
				return if (viewModel == null) {
					val activity = fragment.requireActivity()
					val vmClass = getViewModelClass(activity)
					ViewModelProvider.create(
						store = activity.viewModelStore,
						factory = activity.defaultViewModelProviderFactory,
						extras = activity.defaultViewModelCreationExtras,
					)[vmClass].also { cached = it }
				} else {
					viewModel
				}
			}

		override fun isInitialized(): Boolean = cached != null

		private fun getViewModelClass(activity: Activity) = when (activity) {
			is ReaderActivity -> ReaderViewModel::class.java
			is DetailsExpressiveActivity -> DetailsViewModel::class.java
			else -> error("Wrong activity ${activity.javaClass.simpleName} for ${ChaptersPagesViewModel::class.java.simpleName}")
		}
	}

	enum class ChapterOpenMode {
		NORMAL, ASK
	}
}

/**
 * "Sort by name": natural order on the name the row actually shows ("Chapter 9" before "Chapter 10"),
 * falling back to the chapter number for sources that ship untitled chapters, with volume/number as
 * the tiebreaker.
 */
private val CHAPTER_NAME_COMPARATOR: Comparator<ChapterListItem> =
	compareBy(AlphanumComparator()) { it: ChapterListItem -> it.sortKey() }
		.thenBy { it.chapter.volume }
		.thenBy { it.chapter.number }

private fun ChapterListItem.sortKey(): String = chapter.title?.takeIf { it.isNotBlank() }
	?: chapter.numberString().orEmpty()
