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
import javax.inject.Inject
import javax.inject.Singleton

data class DeveloperToolsUiState(
	val isRunning: Boolean = false,
	val completed: Int = 0,
	val total: Int = 0,
	val results: List<DeveloperExtensionTestResult> = emptyList(),
	val errorMessage: String? = null,
	val hasRun: Boolean = false,
)

@HiltViewModel
class DeveloperToolsViewModel @Inject constructor(
	private val controller: DeveloperToolsController,
) : BaseViewModel() {

	val uiState: StateFlow<DeveloperToolsUiState> = controller.uiState

	fun runAll() = controller.runAll()

	fun cancel() = controller.cancel()
}

@Singleton
class DeveloperToolsController @Inject constructor(
	private val runner: DeveloperExtensionTestRunner,
) {

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
	private val _uiState = MutableStateFlow(DeveloperToolsUiState())
	val uiState: StateFlow<DeveloperToolsUiState> = _uiState.asStateFlow()

	private var runJob: Job? = null

	fun runAll() {
		if (runJob?.isActive == true) return
		_uiState.update {
			DeveloperToolsUiState(isRunning = true)
		}
		val job = scope.launch(start = CoroutineStart.LAZY) {
			try {
				val results = runner.run(
					onPrepared = { pending ->
					_uiState.update { state ->
						state.copy(
							completed = 0,
							total = pending.size,
							results = pending,
						)
					}
				},
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
						hasRun = true,
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
						hasRun = true,
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
