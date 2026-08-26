package org.koitharu.kotatsu.reader.ui.tts

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koitharu.kotatsu.core.prefs.AppSettings
import java.text.BreakIterator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speaks the text of the novel reader, sentence by sentence. The reader feeds it one chapter of
 * plain text and follows [position] to highlight and scroll; everything else (chunking, queueing,
 * the engine lifecycle) lives here so both the reader and the foreground service can drive it.
 */
@Singleton
class ReaderTts @Inject constructor(
	@ApplicationContext private val context: Context,
	private val settings: AppSettings,
) : TextToSpeech.OnInitListener {

	/** A sentence of the current chapter: [start] until [end] are offsets into the chapter text. */
	data class Position(val chapter: Int, val index: Int, val start: Int, val end: Int)

	private var tts: TextToSpeech? = null
	private var isInitialized = false
	private var chunks: List<Sentence> = emptyList()
	private var text: String = ""
	private var chapter: Int = -1
	private var pendingPlayFrom: Int? = null

	private val _isPlaying = MutableStateFlow(false)
	private val _position = MutableStateFlow<Position?>(null)
	private val _chapterFinished = MutableSharedFlow<Int>(extraBufferCapacity = 1)

	val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
	val position: StateFlow<Position?> = _position.asStateFlow()

	/** Emits the chapter index that just finished, so the reader can move on to the next one. */
	val chapterFinished: SharedFlow<Int> = _chapterFinished.asSharedFlow()

	val isAttached: Boolean
		get() = chapter >= 0

	/**
	 * The handful of voices worth offering for what is currently being read, best first. The engine's
	 * own default is usually its lowest-quality compact voice, which is what makes stock TTS sound
	 * robotic — so a fresh install lands on the best installed voice instead of that one.
	 */
	fun voicePresets(): List<Voice> {
		val all = tts?.takeIf { isInitialized }?.voices ?: return emptyList()
		val language = spokenLocale().language
		val speakers = all.asSequence()
			.filterNot { it.isInstallRequired() }
			.filter { it.locale.language == language }
			.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.latency }.thenBy { it.name })
			// One entry per speaker: engines list the same voice once per variant, and four rows of
			// the same person is what made the picker useless.
			.distinctBy { it.name.substringBeforeLast('-') }
			.toList()
		if (speakers.size <= MAX_PRESETS) {
			return speakers
		}
		// Google's engine tags the gender in the voice name ("en-us-x-sfg#male_1-local"). When it is
		// there, hand out two of each so the four slots are actually different people.
		val male = speakers.filter { it.isMale() }
		val female = speakers.filter { it.isFemale() }
		if (male.isNotEmpty() && female.isNotEmpty()) {
			val perGender = MAX_PRESETS / 2
			val picked = female.take(perGender) + male.take(perGender)
			if (picked.size == MAX_PRESETS) {
				return picked
			}
		}
		// Engine keeps gender to itself: spread the picks across the whole list instead of taking the
		// top four, whose best-ranked entries are near-identical siblings.
		return List(MAX_PRESETS) { speakers[it * (speakers.size - 1) / (MAX_PRESETS - 1)] }
	}

	fun selectedVoiceIndex(): Int = settings.epubTtsVoiceIndex.coerceIn(0, MAX_PRESETS - 1)

	fun selectVoice(index: Int) {
		settings.epubTtsVoiceIndex = index
		applySettings()
		if (_isPlaying.value) {
			// The engine only picks up a new voice on the next utterance, so re-queue from here.
			play()
		}
	}

	/**
	 * Language of the text being read, guessed from its script so novels in other languages get
	 * their own voices instead of an English one mangling them.
	 */
	private fun spokenLocale(): Locale = guessLocale(text) ?: Locale.getDefault()

	/**
	 * Loads a chapter without starting playback. Re-loading the same chapter keeps the position so
	 * a style change that re-renders the reader doesn't restart the speech.
	 */
	fun setChapter(index: Int, chapterText: String) {
		if (chapter == index && text == chapterText) {
			return
		}
		chapter = index
		text = chapterText
		chunks = splitSentences(chapterText)
		_position.value = null
	}

	/** Starts (or restarts) playback from the sentence containing [fromOffset] in the chapter text. */
	fun play(fromOffset: Int = _position.value?.start ?: 0) {
		val index = chunks.indexOfFirst { fromOffset < it.end }.coerceAtLeast(0)
		if (!isInitialized) {
			pendingPlayFrom = index
			ensureEngine()
			return
		}
		speakFrom(index)
	}

	fun pause() {
		tts?.stop()
		_isPlaying.value = false
	}

	fun toggle() {
		if (_isPlaying.value) pause() else play()
	}

	/** Moves [delta] sentences and keeps speaking. */
	fun skip(delta: Int) {
		val current = _position.value?.index ?: 0
		val target = (current + delta).coerceIn(0, (chunks.size - 1).coerceAtLeast(0))
		if (chunks.isEmpty()) {
			return
		}
		_position.value = positionAt(target)
		if (_isPlaying.value || !isInitialized) {
			play(chunks[target].start)
		}
	}

	fun stop() {
		tts?.stop()
		tts?.shutdown()
		tts = null
		isInitialized = false
		pendingPlayFrom = null
		chapter = -1
		text = ""
		chunks = emptyList()
		_position.value = null
		_isPlaying.value = false
	}

	/** Speed and pitch only reach the engine at speak() time, so a live change has to re-queue. */
	fun applyTuning() {
		applySettings()
		if (_isPlaying.value) {
			play()
		}
	}

	fun applySettings() {
		val engine = tts ?: return
		engine.setSpeechRate(settings.epubTtsSpeed)
		engine.setPitch(settings.epubTtsPitch)
		val presets = voicePresets()
		val voice = presets.getOrNull(selectedVoiceIndex()) ?: presets.firstOrNull()
		if (voice != null) {
			engine.voice = voice
		} else {
			// No voice for this script is installed; at least point the engine at the right language.
			engine.language = spokenLocale()
		}
	}

	override fun onInit(status: Int) {
		if (status != TextToSpeech.SUCCESS) {
			isInitialized = false
			pendingPlayFrom = null
			return
		}
		isInitialized = true
		// Speak on the media stream: on the default stream the engine is mixed at notification
		// volume and ducked, which is most of why it sounds thin and quiet.
		tts?.setAudioAttributes(
			AudioAttributes.Builder()
				.setUsage(AudioAttributes.USAGE_MEDIA)
				.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
				.build(),
		)
		tts?.setOnUtteranceProgressListener(ProgressListener())
		applySettings()
		pendingPlayFrom?.let {
			pendingPlayFrom = null
			speakFrom(it)
		}
	}

	private fun ensureEngine() {
		if (tts == null) {
			tts = TextToSpeech(context, this)
		}
	}

	private fun speakFrom(startIndex: Int) {
		val engine = tts ?: return
		if (chunks.isEmpty()) {
			return
		}
		applySettings()
		engine.stop()
		_isPlaying.value = true
		for (i in startIndex..chunks.lastIndex) {
			val queueMode = if (i == startIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
			engine.speak(spokenText(i), queueMode, null, i.toString())
		}
	}

	/** Line wrapping inside a sentence makes engines pause mid-phrase; feed them a clean single line. */
	private fun spokenText(index: Int): String = WHITESPACE
		.replace(text.substring(chunks[index].start, chunks[index].end), " ")
		.trim()

	private fun positionAt(index: Int): Position? = chunks.getOrNull(index)?.let {
		Position(chapter, index, it.start, it.end)
	}

	private inner class ProgressListener : UtteranceProgressListener() {

		override fun onStart(utteranceId: String?) {
			val index = utteranceId?.toIntOrNull() ?: return
			_position.value = positionAt(index)
		}

		override fun onDone(utteranceId: String?) {
			val index = utteranceId?.toIntOrNull() ?: return
			if (index >= chunks.lastIndex) {
				_isPlaying.value = false
				_chapterFinished.tryEmit(chapter)
			}
		}

		@Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, errorCode)"))
		override fun onError(utteranceId: String?) = Unit

		override fun onError(utteranceId: String?, errorCode: Int) {
			_isPlaying.value = false
		}
	}
}

private const val MAX_PRESETS = 4
private val WHITESPACE = Regex("""\s+""")

private fun Voice.isInstallRequired(): Boolean =
	features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true

private fun Voice.isMale(): Boolean = name.contains("#male", ignoreCase = true)

private fun Voice.isFemale(): Boolean = name.contains("#female", ignoreCase = true)

/**
 * Cheap script sniff. Only tells apart the scripts that map to a different voice; anything Latin
 * falls through to the device language, which is the right answer for it.
 */
private fun guessLocale(text: String): Locale? {
	val sample = text.take(2000)
	var hiragana = 0
	var hangul = 0
	var han = 0
	var cyrillic = 0
	var arabic = 0
	for (char in sample) {
		when (Character.UnicodeScript.of(char.code)) {
			Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> hiragana++
			Character.UnicodeScript.HANGUL -> hangul++
			Character.UnicodeScript.HAN -> han++
			Character.UnicodeScript.CYRILLIC -> cyrillic++
			Character.UnicodeScript.ARABIC -> arabic++
			else -> Unit
		}
	}
	return when {
		hiragana > 0 -> Locale.JAPANESE
		hangul > han -> Locale.KOREAN
		han > 0 -> Locale.CHINESE
		cyrillic > sample.length / 4 -> Locale.forLanguageTag("ru")
		arabic > sample.length / 4 -> Locale.forLanguageTag("ar")
		else -> null
	}
}

private class Sentence(val start: Int, val end: Int)

/** Sentence ranges, using the platform breaker so it works for every language the engine speaks. */
private fun splitSentences(text: String): List<Sentence> {
	if (text.isBlank()) {
		return emptyList()
	}
	val iterator = BreakIterator.getSentenceInstance()
	iterator.setText(text)
	val result = ArrayList<Sentence>()
	var start = iterator.first()
	var end = iterator.next()
	while (end != BreakIterator.DONE) {
		// Trim the whitespace at the edges: a range that starts on a line break points at the tail
		// of the previous page, which is the wrong page to highlight and turn to.
		var from = start
		var to = end
		while (from < to && text[from].isWhitespace()) from++
		while (to > from && text[to - 1].isWhitespace()) to--
		if (from < to) {
			result.add(Sentence(from, to))
		}
		start = end
		end = iterator.next()
	}
	return result
}
