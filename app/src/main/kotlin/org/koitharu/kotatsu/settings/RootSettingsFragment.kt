package org.koitharu.kotatsu.settings

import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.fragment.app.activityViewModels
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.addMenuProvider
import org.koitharu.kotatsu.settings.search.SettingsSearchMenuProvider
import org.koitharu.kotatsu.settings.search.SettingsSearchViewModel

@AndroidEntryPoint
class RootSettingsFragment : BasePreferenceFragment(0) {

	private val activityViewModel: SettingsSearchViewModel by activityViewModels()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_root)
		bindPreferenceSummary("appearance", R.string.theme, R.string.list_mode, R.string.language)
		bindPreferenceSummary("reader", R.string.read_mode, R.string.scale_mode, R.string.switch_pages)
		bindPreferenceSummary("network", R.string.storage_usage, R.string.proxy, R.string.prefetch_content)
		bindPreferenceSummary("downloads", R.string.manga_save_location, R.string.downloads_wifi_only)
		bindPreferenceSummary("tracker", R.string.track_sources, R.string.notifications_settings)
		bindPreferenceSummary("services", R.string.suggestions, R.string.tracking)
		bindPreferenceSummary("extensions", R.string.manage_extensions, R.string.nsfw_filter, R.string.sort_order)
		findPreference<Preference>("about")?.summary = getString(R.string.app_version, BuildConfig.VERSION_NAME)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		addMenuProvider(SettingsSearchMenuProvider(activityViewModel))
	}

	override fun setTitle(title: CharSequence?) {
		if (!resources.getBoolean(R.bool.is_tablet)) {
			super.setTitle(title)
		}
	}

	private fun bindPreferenceSummary(key: String, @StringRes vararg items: Int) {
		findPreference<Preference>(key)?.summary = items.joinToString { getString(it) }
	}
}
