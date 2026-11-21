package org.koitharu.kotatsu.reader.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog
import org.koitharu.kotatsu.core.ui.dialog.showTextInputDialog

/** The reader's "search book" popups, shared by the EPUB reader and PDFs. */
object BookSearch {

	const val MAX_RESULTS = 100

	class Result(val title: String, val snippet: String)

	fun askQuery(context: Context, initialValue: String = "", onQuery: (String) -> Unit) = showTextInputDialog(
		context = context,
		icon = R.drawable.ic_search,
		title = context.getString(R.string.epub_search_book),
		initialValue = initialValue,
		hint = context.getString(R.string.epub_search_hint),
	) { onQuery(it.trim()) }

	/** A matching piece of [text] around [start], on one line. */
	fun snippet(text: CharSequence, start: Int, length: Int): String {
		val from = (start - 45).coerceIn(0, text.length)
		val to = (start + length + 70).coerceIn(from, text.length)
		return text.subSequence(from, to).toString().replace(Regex("\\s+"), " ").trim()
	}

	fun showResults(context: Context, results: List<Result>, onClick: (Int) -> Unit) {
		showComposeDialog(context) { dismiss ->
			val colors = MaterialTheme.colorScheme
			ExpressiveDialogCard(
				icon = painterResource(R.drawable.ic_search),
				title = stringResource(R.string.search_results_count, results.size),
				cornerAction = DialogCornerAction(
					icon = painterResource(R.drawable.ic_close),
					label = stringResource(android.R.string.cancel),
					onClick = dismiss,
				),
			) {
				results.forEachIndexed { index, result ->
					ExpressiveOptionGroupItem(
						index = index,
						count = results.size,
						onClick = {
							dismiss()
							onClick(index)
						},
					) {
						Column(modifier = Modifier.padding(vertical = 6.dp)) {
							Text(
								text = result.title,
								style = MaterialTheme.typography.bodyLarge,
								color = colors.onSurface,
							)
							Text(
								text = result.snippet,
								style = MaterialTheme.typography.bodyMedium,
								color = colors.onSurfaceVariant,
								maxLines = 2,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
				}
			}
		}
	}
}
