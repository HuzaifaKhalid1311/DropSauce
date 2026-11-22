package org.koitharu.kotatsu.reader.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.databinding.SheetEpubDictionaryBinding

/** The word lookup sheet of the novel and PDF readers. */
object DictionarySheet {

	private const val DICTIONARY_URL = "https://api.dictionaryapi.dev/api/v2/entries/en"
	private const val MAX_MEANINGS = 4
	private const val MAX_DEFINITIONS_PER_MEANING = 3
	private const val MAX_SYNONYMS = 8

	private val WORD_PATTERN = Regex("[\\p{L}\\p{M}]+(?:['’\\-][\\p{L}\\p{M}]+)*")

	/** A single word, the only thing the dictionary can look up. */
	fun isWord(text: String) = text.matches(WORD_PATTERN)

	fun show(context: Context, scope: CoroutineScope, httpClient: OkHttpClient, word: String) {
		val binding = SheetEpubDictionaryBinding.inflate(LayoutInflater.from(context))
		val dialog = BottomSheetDialog(context)
		// Long meanings open at half the screen at most; the user pulls the sheet up for the rest.
		dialog.behavior.peekHeight = context.resources.displayMetrics.heightPixels / 2
		binding.textViewWord.text = word
		binding.buttonClose.setOnClickListener { dialog.dismiss() }
		dialog.setContentView(binding.root)
		dialog.show()
		scope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) { load(httpClient, word) }
			}
			if (!dialog.isShowing) return@launch
			binding.progressBar.isVisible = false
			binding.scrollViewContent.isVisible = true
			val entry = result.getOrNull()
			binding.textViewContent.text = when {
				result.isFailure -> context.getString(R.string.dictionary_error)
				entry == null -> context.getString(R.string.dictionary_not_found)
				else -> format(context, entry)
			}
			binding.textViewPhonetic.text = entry?.phonetic.orEmpty()
			binding.textViewPhonetic.isVisible = !entry?.phonetic.isNullOrBlank()
			binding.textViewSource.isVisible = entry != null
		}
	}

	private fun load(httpClient: OkHttpClient, word: String): Entry? {
		val url = DICTIONARY_URL.toHttpUrl().newBuilder().addPathSegment(word).build()
		val request = Request.Builder().url(url).build()
		val body = httpClient.newCall(request).execute().use { response ->
			if (response.code == 404) return null
			check(response.isSuccessful) { "Dictionary request failed: ${response.code}" }
			response.body.string()
		}
		return parse(body)
	}

	private fun parse(body: String): Entry? {
		val entry = Json.parseToJsonElement(body).jsonArray.firstOrNull()?.jsonObject ?: return null
		val meanings = entry["meanings"]?.jsonArray.orEmpty().mapNotNull { meaningElement ->
			val meaning = meaningElement.jsonObject
			val definitions = meaning["definitions"]?.jsonArray.orEmpty().mapNotNull { definitionElement ->
				val definition = definitionElement.jsonObject
				val text = definition["definition"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
				Definition(text, definition["example"]?.jsonPrimitive?.contentOrNull)
			}.take(MAX_DEFINITIONS_PER_MEANING)
			if (definitions.isEmpty()) return@mapNotNull null
			Meaning(
				partOfSpeech = meaning["partOfSpeech"]?.jsonPrimitive?.contentOrNull.orEmpty(),
				definitions = definitions,
				synonyms = meaning["synonyms"]?.jsonArray.orEmpty()
					.mapNotNull { it.jsonPrimitive.contentOrNull }.take(MAX_SYNONYMS),
			)
		}.take(MAX_MEANINGS)
		if (meanings.isEmpty()) return null
		return Entry(
			phonetic = entry["phonetic"]?.jsonPrimitive?.contentOrNull.orEmpty(),
			meanings = meanings,
		)
	}

	private fun format(context: Context, entry: Entry): CharSequence {
		val text = SpannableStringBuilder()
		entry.meanings.forEachIndexed { meaningIndex, meaning ->
			if (meaningIndex > 0) text.append("\n\n")
			val headingStart = text.length
			text.append(meaning.partOfSpeech.ifBlank { context.getString(R.string.dictionary) })
			text.setSpan(StyleSpan(Typeface.BOLD), headingStart, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
			meaning.definitions.forEachIndexed { index, definition ->
				text.append("\n${index + 1}. ${definition.text}")
				definition.example?.takeIf(String::isNotBlank)?.let {
					text.append("\n   ${context.getString(R.string.dictionary_example)}: “$it”")
				}
			}
			if (meaning.synonyms.isNotEmpty()) {
				text.append("\n${context.getString(R.string.dictionary_synonyms)}: ${meaning.synonyms.joinToString()}")
			}
		}
		return text
	}

	private data class Entry(val phonetic: String, val meanings: List<Meaning>)
	private data class Meaning(val partOfSpeech: String, val definitions: List<Definition>, val synonyms: List<String>)
	private data class Definition(val text: String, val example: String?)
}
