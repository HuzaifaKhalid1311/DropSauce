package org.haziffe.dropsauce.settings

import android.content.SharedPreferences
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.ui.BasePreferenceFragment
import org.haziffe.dropsauce.settings.utils.MultiAutoCompleteTextViewPreference
import org.haziffe.dropsauce.settings.utils.TagsAutoCompleteProvider
import org.haziffe.dropsauce.suggestions.domain.SuggestionRepository
import org.haziffe.dropsauce.suggestions.ui.SuggestionsWorker
import javax.inject.Inject

@AndroidEntryPoint
class SuggestionsSettingsFragment : BasePreferenceFragment(R.string.suggestions),
	SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var repository: SuggestionRepository

	@Inject
	lateinit var tagsCompletionProvider: TagsAutoCompleteProvider

	@Inject
	lateinit var suggestionsScheduler: SuggestionsWorker.Scheduler

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		settings.subscribe(this)
	}

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_suggestions)

		findPreference<MultiAutoCompleteTextViewPreference>(AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS)?.run {
			autoCompleteProvider = tagsCompletionProvider
			summaryProvider = MultiAutoCompleteTextViewPreference.SimpleSummaryProvider(summary)
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		settings.unsubscribe(this)
	}

	override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
		if (settings.isSuggestionsEnabled && (key == AppSettings.KEY_SUGGESTIONS
				|| key == AppSettings.KEY_SUGGESTIONS_EXCLUDE_TAGS
				|| key == AppSettings.KEY_SUGGESTIONS_EXCLUDE_NSFW)
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
