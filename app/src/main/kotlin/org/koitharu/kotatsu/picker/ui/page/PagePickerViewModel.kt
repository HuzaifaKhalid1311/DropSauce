package org.haziffe.dropsauce.picker.ui.page

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus
import org.haziffe.dropsauce.core.nav.MangaIntent
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.prefs.observeAsStateFlow
import org.haziffe.dropsauce.core.ui.BaseViewModel
import org.haziffe.dropsauce.core.util.ext.firstNotNull
import org.haziffe.dropsauce.details.data.MangaDetails
import org.haziffe.dropsauce.details.domain.DetailsLoadUseCase
import org.haziffe.dropsauce.details.ui.pager.pages.PageThumbnail
import org.haziffe.dropsauce.list.ui.model.ListHeader
import org.haziffe.dropsauce.list.ui.model.ListModel
import org.haziffe.dropsauce.reader.domain.ChaptersLoader
import javax.inject.Inject

@HiltViewModel
class PagePickerViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	private val chaptersLoader: ChaptersLoader,
	private val detailsLoadUseCase: DetailsLoadUseCase,
	settings: AppSettings,
) : BaseViewModel() {

	private val intent = MangaIntent(savedStateHandle)

	private var loadingJob: Job? = null
	private var loadingNextJob: Job? = null

	val thumbnails = MutableStateFlow<List<ListModel>>(emptyList())
	val isLoadingDown = MutableStateFlow(false)
	val manga = MutableStateFlow(intent.manga?.let { MangaDetails(it) })

	val isNoChapters = manga.map {
		it != null && it.isLoaded && it.allChapters.isEmpty()
	}

	val gridScale = settings.observeAsStateFlow(
		scope = viewModelScope + Dispatchers.Default,
		key = AppSettings.KEY_GRID_SIZE_PAGES,
		valueProducer = { gridSizePages / 100f },
	)

	init {
		loadingJob = launchLoadingJob(Dispatchers.Default) {
			doInit()
		}
	}

	private suspend fun doInit() {
		val details = detailsLoadUseCase.invoke(intent, force = false)
			.onEach { manga.value = it }
			.first { x -> x.isLoaded }
		chaptersLoader.init(details)
		val initialChapterId = details.allChapters.firstOrNull()?.id ?: return
		if (!chaptersLoader.hasPages(initialChapterId)) {
			chaptersLoader.loadSingleChapter(initialChapterId)
		}
		updateList()
	}

	fun loadNextChapter() {
		if (loadingJob?.isActive == true || loadingNextJob?.isActive == true) {
			return
		}
		loadingNextJob = launchJob(Dispatchers.Default) {
			isLoadingDown.value = true
			try {
				val currentId = chaptersLoader.last().chapterId
				chaptersLoader.loadPrevNextChapter(manga.firstNotNull(), currentId, isNext = true)
				updateList()
			} finally {
				isLoadingDown.value = false
			}
		}
	}

	private fun updateList() {
		val snapshot = chaptersLoader.snapshot()
		val pages = buildList(snapshot.size + chaptersLoader.size + 2) {
			var previousChapterId = 0L
			for (page in snapshot) {
				if (page.chapterId != previousChapterId) {
					chaptersLoader.peekChapter(page.chapterId)?.let {
						add(ListHeader(it))
					}
					previousChapterId = page.chapterId
				}
				this += PageThumbnail(
					isCurrent = false,
					page = page,
				)
			}
		}
		thumbnails.value = pages
	}
}
