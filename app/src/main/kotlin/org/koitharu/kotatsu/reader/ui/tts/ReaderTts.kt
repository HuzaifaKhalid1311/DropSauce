package org.koitharu.kotatsu.reader.ui.tts

import android.content.Context
import android.content.Intent
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
import org.koitharu.kotatsu.R
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

	/** How much of the current sentence the engine has already spoken, in characters. */
	private var spokenOffset = 0

	/** Characters dropped from the front of the current utterance, which engine offsets exclude. */
	private var spokenPrefix = 0

	private val _isPlaying = MutableStateFlow(false)
	private val _position = MutableStateFlow<Position?>(null)
	private val _chapterFinished = MutableSharedFlow<Int>(extraBufferCapacity = 1)
	private val _previewingVoice = MutableStateFlow<String?>(null)

	val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
	val position: StateFlow<Position?> = _position.asStateFlow()

	/** Emits the chapter index that just finished, so the reader can move on to the next one. */
	val chapterFinished: SharedFlow<Int> = _chapterFinished.asSharedFlow()

	/** Name of the voice whose sample is playing in the voice picker, if any. */
	val previewingVoice: StateFlow<String?> = _previewingVoice.asStateFlow()

	val isAttached: Boolean
		get() = chapter >= 0

	/**
	 * The voice the user linked to [slot] for what is being read, or null for the engine's own default
	 * voice. Nothing is picked automatically: engines list more voice names than they have voices, so
	 * only the user's ears can tell which ones are worth a slot.
	 */
	fun slotVoice(slot: Int): Voice? {
		val name = linkedVoiceName(slot) ?: return null
		return availableVoices().firstOrNull { it.name == name }
	}

	/** Every voice the engine has ready for the language being read, offline ones first, for the picker. */
	fun availableVoices(): List<Voice> {
		val all = engineVoices() ?: return emptyList()
		val language = spokenLocale().language
		val country = Locale.getDefault().country
		return all
			// Google's "<lang>-language" entry is an alias of whichever voice is the engine default
			.filter { !it.isInstallRequired() && it.locale.language == language && LEGACY_LANGUAGE_VOICE !in it.features.orEmpty() }
			.sortedWith(
				compareBy<Voice> { it.isNetworkConnectionRequired }
					.thenBy { !it.locale.country.equals(country, ignoreCase = true) }
					.thenBy { it.name },
			)
	}

	/** Name of the voice the user linked to [slot], or null when it is on the automatic pick. */
	fun linkedVoiceName(slot: Int): String? =
		settings.getTtsSlotVoices(spokenLocale().language).getOrNull(slot)?.takeIf { it.isNotEmpty() }

	/** Links [voice] to [slot] for the language being read; null puts the slot back on the automatic pick. */
	fun linkVoice(slot: Int, voice: Voice?) {
		settings.setTtsSlotVoice(spokenLocale().language, slot, voice?.name)
		if (slot == selectedVoiceIndex()) {
			selectVoice(slot)
		}
	}

	/** Speaks a line of the book in [voice] so it can be heard before linking it. Pauses the reading. */
	fun preview(voice: Voice) {
		val engine = tts?.takeIf { isInitialized } ?: return
		if (_isPlaying.value) {
			pause()
		}
		engine.setSpeechRate(settings.epubTtsSpeed)
		engine.setPitch(settings.epubTtsPitch)
		engine.voice = voice
		_previewingVoice.value = voice.name
		engine.speak(sampleText(), TextToSpeech.QUEUE_FLUSH, null, PREVIEW_UTTERANCE_PREFIX + voice.name)
	}

	fun stopPreview() {
		_previewingVoice.value = null
		if (!_isPlaying.value) {
			tts?.stop()
		}
		applySettings()
	}

	/** Opens the engine's own screen for downloading voices. */
	fun installVoicesIntent(): Intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
		.setPackage(tts?.defaultEngine)

	/** Some engines throw or hand back null here instead of an empty set; either way there are no voices. */
	private fun engineVoices(): Set<Voice>? = runCatching { tts?.takeIf { isInitialized }?.voices }.getOrNull()

	/** A line or two from where the reader is, so a voice is judged on the book itself. */
	private fun sampleText(): String {
		val from = _position.value?.index ?: 0
		val sample = StringBuilder()
		for (i in from..chunks.lastIndex) {
			if (sample.length >= SAMPLE_LENGTH) break
			sample.append(spokenText(i)).append(' ')
		}
		return sample.trim().takeIf { it.isNotEmpty() }?.toString()
			?: context.getString(R.string.tts_voice_sample)
	}

	fun selectedVoiceIndex(): Int = settings.epubTtsVoiceIndex.coerceIn(0, VOICE_SLOTS - 1)

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

	/**
	 * Speed and pitch only reach the engine at speak() time, so a live change has to re-queue.
	 * Re-queues from the word currently being spoken instead of the start of the sentence, so the
	 * new value is heard right away and the listener does not lose their place.
	 */
	fun applyTuning() {
		applySettings()
		if (_isPlaying.value) {
			val index = _position.value?.index ?: return play()
			speakFrom(index, skipChars = spokenOffset)
		}
	}

	fun applySettings() {
		val engine = tts ?: return
		engine.setSpeechRate(settings.epubTtsSpeed)
		engine.setPitch(settings.epubTtsPitch)
		val voice = slotVoice(selectedVoiceIndex())
		if (voice != null) {
			engine.voice = voice
		} else {
			// No voice linked to this slot: the engine's default voice for the book's language.
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

	/**
	 * @param skipChars characters of the first sentence already spoken; they are dropped so a
	 * re-queue continues mid-sentence rather than repeating it.
	 */
	private fun speakFrom(startIndex: Int, skipChars: Int = 0) {
		val engine = tts ?: return
		if (chunks.isEmpty()) {
			return
		}
		applySettings()
		engine.stop()
		_isPlaying.value = true
		spokenPrefix = skipChars
		spokenOffset = skipChars
		for (i in startIndex..chunks.lastIndex) {
			val queueMode = if (i == startIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
			val sentence = spokenText(i).let { if (i == startIndex) it.drop(skipChars) else it }
			engine.speak(sentence, queueMode, null, i.toString())
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

		/** A sample ending clears the picker's pause button, unless a newer sample already took over. */
		private fun onPreviewEnded(utteranceId: String?): Boolean {
			val name = utteranceId?.removePrefix(PREVIEW_UTTERANCE_PREFIX)?.takeIf { it != utteranceId } ?: return false
			_previewingVoice.compareAndSet(name, null)
			return true
		}

		override fun onStop(utteranceId: String?, interrupted: Boolean) {
			onPreviewEnded(utteranceId)
		}

		override fun onStart(utteranceId: String?) {
			val index = utteranceId?.toIntOrNull() ?: return
			// Only the utterance a re-queue started mid-sentence carries a prefix; every one after
			// it is spoken whole.
			if (_position.value?.index != index) {
				spokenPrefix = 0
			}
			spokenOffset = spokenPrefix
			_position.value = positionAt(index)
		}

		override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
			spokenOffset = spokenPrefix + start
		}

		override fun onDone(utteranceId: String?) {
			if (onPreviewEnded(utteranceId)) return
			val index = utteranceId?.toIntOrNull() ?: return
			if (index >= chunks.lastIndex) {
				_isPlaying.value = false
				_chapterFinished.tryEmit(chapter)
			}
		}

		@Deprecated("Deprecated in Java", ReplaceWith("onError(utteranceId, errorCode)"))
		override fun onError(utteranceId: String?) = Unit

		override fun onError(utteranceId: String?, errorCode: Int) {
			if (onPreviewEnded(utteranceId)) return
			_isPlaying.value = false
		}
	}
}

internal const val VOICE_SLOTS = 4
private const val SAMPLE_LENGTH = 120
private const val PREVIEW_UTTERANCE_PREFIX = "preview:"
private const val LEGACY_LANGUAGE_VOICE = "legacySetLanguageVoice"
private val WHITESPACE = Regex("""\s+""")

private fun Voice.isInstallRequired(): Boolean =
	features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true

/**
 * Engines don't expose gender, so read it off the name for the voice picker. Older Google builds and
 * Samsung tag it ("…#female_1-local", "en-US-SMTf00"); current Google voices only carry a speaker code.
 * shortcut: code table only covers Google's English speakers; other voices show no gender.
 */
internal fun Voice.isFemale(): Boolean? {
	val lower = name.lowercase(Locale.ROOT)
	return when {
		"#female" in lower || "smtf" in lower -> true
		"#male" in lower || "smtm" in lower -> false
		else -> when (speakerCode()) {
			in GOOGLE_FEMALE -> true
			in GOOGLE_MALE -> false
			else -> null
		}
	}
}

/** "en-us-x-tpc-local" → "tpc". */
internal fun Voice.speakerCode(): String? = name.lowercase(Locale.ROOT).split('-').getOrNull(3)

private val GOOGLE_FEMALE = setOf(
	"sfg", "iob", "iog", "tpc", "tpf", // en-US
	"gba", "gbc", "gbg", // en-GB
	"afh", "aua", "auc", // en-AU
	"ahp", "cxx", "ena", "enc", // en-IN
)
private val GOOGLE_MALE = setOf(
	"iol", "iom", "tpd", // en-US
	"gbb", "gbd", "rjs", // en-GB
	"aub", "aud", // en-AU
	"end", "ene", // en-IN
)


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
