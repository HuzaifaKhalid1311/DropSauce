package org.koitharu.kotatsu.settings.developer

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.mihon.model.MihonMangaSource
import javax.inject.Inject
import javax.inject.Singleton

data class DeveloperToolsUiState(
	val isRunning: Boolean = false,
	val completed: Int = 0,
	val total: Int = 0,
	val results: List<DeveloperExtensionTestResult> = emptyList(),
	val errorMessage: String? = null,
)

@HiltViewModel
class DeveloperToolsViewModel @Inject constructor(
	private val controller: DeveloperToolsController,
) : BaseViewModel() {

	val uiState: StateFlow<DeveloperToolsUiState> = controller.uiState

	init {
		controller.load()
	}

	fun runAll() = controller.runAll()

	fun cancel() = controller.cancel()

	fun runOne(packageName: String) = controller.runOne(packageName)

	fun cancelOne(packageName: String) = controller.cancelOne(packageName)
}

@Singleton
class DeveloperToolsController @Inject constructor(
	private val runner: DeveloperExtensionTestRunner,
) {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val _uiState = MutableStateFlow(DeveloperToolsUiState())
	val uiState: StateFlow<DeveloperToolsUiState> = _uiState.asStateFlow()

	private var runJob: Job? = null
	private var loadJob: Job? = null
	private val singleJobs = HashMap<String, Job>()
	private var targets: List<SelectedExtensionTest<MihonMangaSource>> = emptyList()

	/** Lists every installed extension up front, so each row can be tested on its own. */
	fun load() {
		if (targets.isNotEmpty() || loadJob?.isActive == true) return
		loadJob = scope.launch {
			try {
				val prepared = runner.prepareTargets()
				targets = prepared
				_uiState.update { state ->
					state.copy(
						total = prepared.size,
						results = prepared.map(runner::pendingResultOf),
					)
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Throwable) {
				_uiState.update { it.copy(errorMessage = e.message ?: e.javaClass.simpleName) }
			}
		}
	}

	fun runAll() {
		if (runJob?.isActive == true || singleJobs.values.any { it.isActive }) return
		val prepared = targets
		if (prepared.isEmpty()) {
			load()
			return
		}
		_uiState.update {
			DeveloperToolsUiState(
				isRunning = true,
				total = prepared.size,
				results = prepared.map(runner::pendingResultOf),
			)
		}
		val job = scope.launch(start = CoroutineStart.LAZY) {
			try {
				val results = runner.run(
					targets = prepared,
					onStarted = { packageName ->
					updateResult(packageName) { it.copy(state = DeveloperExtensionStatus.RUNNING) }
				},
					onResult = { result ->
					updateResult(result.packageName) { result }
				},
			)
				_uiState.update {
					it.copy(
						isRunning = false,
						completed = results.size,
						total = results.size,
						results = results,
					)
				}
			} catch (e: CancellationException) {
				markStopped()
				throw e
			} catch (e: Throwable) {
				_uiState.update {
					it.copy(
						isRunning = false,
						errorMessage = e.message ?: e.javaClass.simpleName,
					)
				}
			}
		}
		runJob = job
		job.invokeOnCompletion {
			if (runJob === job) runJob = null
		}
		job.start()
	}

	fun cancel() {
		runJob?.cancel()
		markStopped()
	}

	fun runOne(packageName: String) {
		if (_uiState.value.isRunning || singleJobs[packageName]?.isActive == true) return
		val target = targets.firstOrNull { it.packageName == packageName } ?: return
		updateResult(packageName) {
			it.copy(state = DeveloperExtensionStatus.RUNNING, stages = emptyList(), durationMillis = 0)
		}
		val job = scope.launch(start = CoroutineStart.LAZY) {
			try {
				val result = runner.test(target)
				updateResult(packageName) { result }
			} catch (e: CancellationException) {
				updateResult(packageName) { it.copy(state = DeveloperExtensionStatus.PENDING) }
				throw e
			} catch (e: Throwable) {
				updateResult(packageName) {
					it.copy(
						state = null,
						stages = listOf(
							DeveloperTestStageResult(
								name = "Extension loading",
								status = DeveloperTestStageStatus.FAILED,
								message = e.message ?: e.javaClass.simpleName,
								durationMillis = 0,
							),
						),
					)
				}
			}
		}
		singleJobs[packageName] = job
		job.invokeOnCompletion {
			if (singleJobs[packageName] === job) singleJobs.remove(packageName)
		}
		job.start()
	}

	fun cancelOne(packageName: String) {
		singleJobs.remove(packageName)?.cancel()
		updateResult(packageName) {
			if (it.status == DeveloperExtensionStatus.RUNNING) {
				it.copy(state = DeveloperExtensionStatus.PENDING)
			} else {
				it
			}
		}
	}

	private fun updateResult(
		packageName: String,
		transform: (DeveloperExtensionTestResult) -> DeveloperExtensionTestResult,
	) {
		_uiState.update { state ->
			val results = state.results.map { if (it.packageName == packageName) transform(it) else it }
			state.copy(
				results = results,
				completed = results.count { it.status.isFinished },
			)
		}
	}

	private fun markStopped() {
		_uiState.update { state ->
			state.copy(
				isRunning = false,
				results = state.results.map {
					if (it.status == DeveloperExtensionStatus.RUNNING) {
						it.copy(state = DeveloperExtensionStatus.PENDING)
					} else {
						it
					}
				},
			)
		}
	}
}

private val DeveloperExtensionStatus.isFinished: Boolean
	get() = this == DeveloperExtensionStatus.PASSED ||
		this == DeveloperExtensionStatus.BLOCKED ||
		this == DeveloperExtensionStatus.ERROR
