package org.koitharu.kotatsu.reader.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog

/** The reader's text search popup, shared by the EPUB reader and PDFs: the field on top, its results below. */
object BookSearch {

	const val MAX_RESULTS = 100

	class Result(val title: String, val snippet: String)

	/** A piece of [text] around the match at [start], on one line; the match comes early so two lines always show it. */
	fun snippet(text: CharSequence, start: Int, length: Int): String {
		val begin = start.coerceIn(0, text.length)
		var from = (begin - 30).coerceAtLeast(0)
		// start on a whole word
		if (from > 0) from = text.indexOfAny(charArrayOf(' ', '\n'), from).takeIf { it in 0 until begin }?.plus(1) ?: from
		val to = (begin + length + 90).coerceIn(begin, text.length)
		val piece = text.subSequence(from.coerceAtMost(begin), to).toString().replace(Regex("\\s+"), " ").trim()
		return (if (from > 0) "…" else "") + piece + (if (to < text.length) "…" else "")
	}

	/** [snippet] with every [query] in it marked. */
	private fun marked(snippet: String, query: String, style: SpanStyle) = buildAnnotatedString {
		append(snippet)
		if (query.isEmpty()) return@buildAnnotatedString
		var from = 0
		while (true) {
			val match = snippet.indexOf(query, from, ignoreCase = true)
			if (match < 0) break
			addStyle(style, match, match + query.length)
			from = match + query.length
		}
	}

	/** [search] runs for each query typed; picking a result closes the popup and calls [onPick] with its index. */
	fun show(
		context: Context,
		initialQuery: String = "",
		search: suspend (String) -> List<Result>,
		onPick: (query: String, index: Int) -> Unit,
	) = showComposeDialog(context) { dismiss ->
		var query by remember { mutableStateOf(initialQuery) }
		var searched by remember { mutableStateOf<Pair<String, List<Result>>?>(null) }
		var isSearching by remember { mutableStateOf(false) }
		val scope = rememberCoroutineScope()
		val keyboard = LocalSoftwareKeyboardController.current
		val colors = MaterialTheme.colorScheme
		val submit = {
			val text = query.trim()
			if (text.isNotEmpty() && !isSearching) {
				keyboard?.hide()
				isSearching = true
				scope.launch {
					try {
						searched = text to search(text)
					} finally {
						isSearching = false
					}
				}
			}
		}
		ExpressiveDialogCard(
			icon = painterResource(R.drawable.ic_search),
			title = stringResource(R.string.search_text),
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_search_go),
				label = stringResource(R.string.search),
				enabled = query.isNotBlank() && !isSearching,
				onClick = submit,
			),
			pinnedTopContent = {
				OutlinedTextField(
					value = query,
					onValueChange = { query = it },
					placeholder = { Text(stringResource(R.string.epub_search_hint)) },
					singleLine = true,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
					keyboardActions = KeyboardActions(onSearch = { submit() }),
					shape = RoundedCornerShape(16.dp),
					modifier = Modifier.fillMaxWidth(),
				)
				if (isSearching) {
					Spacer(Modifier.height(12.dp))
					LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
				}
			},
			content = searched?.let { (text, results) ->
				{
					val matchStyle = SpanStyle(
						background = colors.tertiaryContainer,
						color = colors.onTertiaryContainer,
						fontWeight = FontWeight.SemiBold,
					)
					Text(
						text = if (results.isEmpty()) {
							stringResource(R.string.epub_no_search_results)
						} else {
							stringResource(R.string.search_results_count, results.size)
						},
						style = MaterialTheme.typography.labelLarge,
						color = colors.onSurfaceVariant,
					)
					results.forEachIndexed { index, result ->
						if (index > 0) HorizontalDivider(color = colors.outlineVariant)
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									dismiss()
									onPick(text, index)
								}
								.padding(vertical = 10.dp),
						) {
							Text(
								text = result.title,
								style = MaterialTheme.typography.bodyLarge,
								color = colors.onSurface,
							)
							Text(
								text = marked(result.snippet, text, matchStyle),
								style = MaterialTheme.typography.bodyMedium,
								color = colors.onSurfaceVariant,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
				}
			},
		)
	}
}
