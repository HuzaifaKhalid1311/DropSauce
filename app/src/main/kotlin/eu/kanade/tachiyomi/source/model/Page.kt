package eu.kanade.tachiyomi.source.model

import android.net.Uri
import eu.kanade.tachiyomi.network.ProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
open class Page(
	val index: Int,
	val url: String = "",
	var imageUrl: String? = null,
	// Deprecated but kept for extension compatibility — excluded from serialization
	@Transient var uri: Uri? = null,
) : ProgressListener {
	val number: Int
		get() = index + 1

	/**
	 * A novel chapter's text. A body property rather than a constructor parameter, exactly as in
	 * Tsundoku, so the four-argument constructor every existing extension compiles against stays
	 * binary-compatible.
	 */
	@Transient
	var text: String? = null

	@Transient
	private val _statusFlow = MutableStateFlow<State>(State.Queue)
	@Transient
	val statusFlow = _statusFlow.asStateFlow()
	var status: State
		get() = _statusFlow.value
		set(value) {
			_statusFlow.value = value
		}

	@Transient
	private val _progressFlow = MutableStateFlow(0)
	@Transient
	val progressFlow = _progressFlow.asStateFlow()
	var progress: Int
		get() = _progressFlow.value
		set(value) {
			_progressFlow.value = value
		}

	override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
		progress = if (contentLength > 0) {
			(100 * bytesRead / contentLength).toInt()
		} else {
			-1
		}
	}

	sealed interface State {
		data object Queue : State
		data object LoadPage : State
		data object DownloadImage : State
		data object Ready : State
		data class Error(val error: Throwable) : State
	}
}
