package org.koitharu.kotatsu.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Filter
import android.widget.MultiAutoCompleteTextView
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.parsers.util.replaceWith
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import org.koitharu.kotatsu.settings.utils.AutoCompleteProvider
import org.koitharu.kotatsu.settings.utils.TagsAutoCompleteProvider
import org.koitharu.kotatsu.suggestions.domain.SuggestionRepository
import org.koitharu.kotatsu.suggestions.ui.SuggestionsWorker
import javax.inject.Inject

@AndroidEntryPoint
class SuggestionsSettingsFragment :
	BaseComposeSettingsFragment(R.string.suggestions),
	SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var repository: SuggestionRepository

	@Inject
	lateinit var tagsCompletionProvider: TagsAutoCompleteProvider

	@Inject
	lateinit var suggestionsScheduler: SuggestionsWorker.Scheduler

	@Inject
	lateinit var settings: AppSettings

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		settings.subscribe(this)
	}

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = ComposeView(requireContext()).apply {
		setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		setContent {
			DropSauceTheme {
				SuggestionsScreen(
					tagsProvider = tagsCompletionProvider,
				)
			}
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		settings.unsubscribe(this)
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (settings.isSuggestionsEnabled && (key == AppSettings.KEY_SUGGESTIONS
				|| key == AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS
				|| key == AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW
				|| key == AppSettings.KEY_SUGGESTIONS_EXCLUDE_NOVELS)
		) {
			updateSuggestions()
		}
	}

	private fun updateSuggestions() {
		lifecycleScope.launch(Dispatchers.Default) {
			suggestionsScheduler.startNow()
		}
	}
}

@Composable
private fun SuggestionsScreen(
	tagsProvider: TagsAutoCompleteProvider,
) {
	var enabled by rememberBooleanPref(AppSettings.KEY_SUGGESTIONS, false)
	var wifiOnly by rememberBooleanPref(AppSettings.KEY_SUGGESTIONS_WIFI_ONLY, false)
	var notifications by rememberBooleanPref(AppSettings.KEY_SUGGESTIONS_NOTIFICATIONS, false)
	var excludeTags by rememberStringPref(AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS, "")
	var excludeNsfw by rememberBooleanPref(AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW, false)
	var excludeNovels by rememberBooleanPref(AppSettings.KEY_SUGGESTIONS_EXCLUDE_NOVELS, true)

	var showTagsDialog by remember { mutableStateOf(false) }

	SettingsScaffold {
		item {
			SettingsGroup {
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.suggestions_enable),
						checked = enabled,
						onCheckedChange = { enabled = it },
						icon = R.drawable.ic_suggestion,
						shape = pos.shape,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.only_using_wifi),
						subtitle = stringResource(R.string.suggestions_wifi_only_summary),
						checked = wifiOnly,
						onCheckedChange = { wifiOnly = it },
						icon = R.drawable.ic_network_cellular,
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.notifications_enable),
						subtitle = stringResource(R.string.suggestions_notifications_summary),
						checked = notifications,
						onCheckedChange = { notifications = it },
						icon = R.drawable.ic_notification,
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.suggestions_excluded_genres),
						subtitle = excludeTags.trimEnd(' ', ',').ifBlank {
							stringResource(R.string.suggestions_excluded_genres_summary)
						},
						icon = R.drawable.ic_tag,
						shape = pos.shape,
						enabled = enabled,
						onClick = { showTagsDialog = true },
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.exclude_nsfw_from_suggestions),
						subtitle = stringResource(R.string.exclude_nsfw_from_suggestions_summary),
						checked = excludeNsfw,
						onCheckedChange = { excludeNsfw = it },
						icon = R.drawable.ic_nsfw,
						shape = pos.shape,
						enabled = enabled,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.exclude_novels_from_suggestions),
						subtitle = stringResource(R.string.exclude_novels_from_suggestions_summary),
						checked = excludeNovels,
						onCheckedChange = { excludeNovels = it },
						icon = R.drawable.ic_auto_stories,
						shape = pos.shape,
						enabled = enabled,
					)
				}
			}
		}
		item {
			PlainInfoSettingsItem(
				text = stringResource(R.string.suggestions_info),
				icon = R.drawable.ic_info_outline,
			)
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}

	if (showTagsDialog) {
		TagsInputDialog(
			initialValue = excludeTags,
			provider = tagsProvider,
			onConfirm = { excludeTags = it },
			onDismiss = { showTagsDialog = false },
		)
	}
}

@Composable
private fun TagsInputDialog(
	initialValue: String,
	provider: AutoCompleteProvider,
	onConfirm: (String) -> Unit,
	onDismiss: () -> Unit,
) {
	var editText by remember { mutableStateOf<MultiAutoCompleteTextView?>(null) }
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.suggestions_excluded_genres)) },
		text = {
			AndroidView(
				modifier = Modifier.fillMaxWidth(),
				factory = { ctx ->
					MultiAutoCompleteTextView(ctx).apply {
						setTokenizer(MultiAutoCompleteTextView.CommaTokenizer())
						setText(initialValue)
						threshold = 1
						setAdapter(TagsCompletionAdapter(ctx, provider))
						editText = this
					}
				},
			)
		},
		confirmButton = {
			TextButton(onClick = {
				onConfirm(editText?.text?.toString().orEmpty())
				onDismiss()
			}) { Text(stringResource(android.R.string.ok)) }
		},
		dismissButton = {
			TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
		},
	)
}

private class TagsCompletionAdapter(
	context: Context,
	private val provider: AutoCompleteProvider,
	private val dataset: MutableList<String> = ArrayList(),
) : ArrayAdapter<String>(context, android.R.layout.simple_dropdown_item_1line, dataset) {

	override fun getFilter(): Filter = object : Filter() {
		override fun performFiltering(constraint: CharSequence?): FilterResults {
			val query = constraint?.toString().orEmpty()
			val suggestions = runBlocking { provider.getSuggestions(query) }
			return FilterResults().apply {
				values = suggestions
				count = suggestions.size
			}
		}

		override fun publishResults(constraint: CharSequence?, results: FilterResults) {
			@Suppress("UNCHECKED_CAST")
			val list = results.values as? List<String> ?: emptyList()
			dataset.replaceWith(list)
			notifyDataSetChanged()
		}
	}
}
