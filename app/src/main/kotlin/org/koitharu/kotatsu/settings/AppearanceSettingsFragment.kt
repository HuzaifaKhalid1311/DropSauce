package org.haziffe.dropsauce.settings

import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatDelegate
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.TwoStatePreference
import dagger.hilt.android.AndroidEntryPoint
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.os.AppShortcutManager
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.prefs.ListMode
import org.haziffe.dropsauce.core.prefs.ProgressIndicatorMode
import org.haziffe.dropsauce.core.prefs.ScreenshotsPolicy
import org.haziffe.dropsauce.core.prefs.SearchSuggestionType
import org.haziffe.dropsauce.core.prefs.TriStateOption
import org.haziffe.dropsauce.core.ui.BasePreferenceFragment
import org.haziffe.dropsauce.core.ui.util.ActivityRecreationHandle
import org.haziffe.dropsauce.core.util.LocaleComparator
import org.haziffe.dropsauce.core.util.ext.getLocalesConfig
import org.haziffe.dropsauce.core.util.ext.postDelayed
import org.haziffe.dropsauce.core.util.ext.setDefaultValueCompat
import org.haziffe.dropsauce.core.util.ext.sortedWithSafe
import org.haziffe.dropsauce.core.util.ext.toList
import org.haziffe.dropsauce.parsers.util.mapToSet
import org.haziffe.dropsauce.parsers.util.names
import org.haziffe.dropsauce.parsers.util.toTitleCase
import org.haziffe.dropsauce.settings.protect.ProtectSetupActivity
import org.haziffe.dropsauce.settings.utils.ActivityListPreference
import org.haziffe.dropsauce.settings.utils.MultiSummaryProvider
import org.haziffe.dropsauce.settings.utils.PercentSummaryProvider
import org.haziffe.dropsauce.settings.utils.SliderPreference
import javax.inject.Inject

@AndroidEntryPoint
class AppearanceSettingsFragment :
    BasePreferenceFragment(R.string.appearance),
    SharedPreferences.OnSharedPreferenceChangeListener {

    @Inject
    lateinit var activityRecreationHandle: ActivityRecreationHandle

    @Inject
    lateinit var appShortcutManager: AppShortcutManager

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_appearance)
        findPreference<SliderPreference>(AppSettings.KEY_GRID_SIZE)?.summaryProvider = PercentSummaryProvider()
        findPreference<ListPreference>(AppSettings.KEY_LIST_MODE)?.run {
            entryValues = ListMode.entries.names()
            setDefaultValueCompat(ListMode.GRID.name)
        }
        findPreference<ListPreference>(AppSettings.KEY_PROGRESS_INDICATORS)?.run {
            entryValues = ProgressIndicatorMode.entries.names()
            setDefaultValueCompat(ProgressIndicatorMode.PERCENT_READ.name)
        }
        findPreference<ActivityListPreference>(AppSettings.KEY_APP_LOCALE)?.run {
            initLocalePicker(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activityIntent = Intent(
                    Settings.ACTION_APP_LOCALE_SETTINGS,
                    Uri.fromParts("package", context.packageName, null),
                )
            }
            summaryProvider = Preference.SummaryProvider<ActivityListPreference> {
                val locale = AppCompatDelegate.getApplicationLocales().get(0)
                locale?.getDisplayName(locale)?.toTitleCase(locale) ?: getString(R.string.follow_system)
            }
            setDefaultValueCompat("")
        }
        findPreference<MultiSelectListPreference>(AppSettings.KEY_MANGA_LIST_BADGES)?.run {
            summaryProvider = MultiSummaryProvider(R.string.none)
        }
        findPreference<Preference>(AppSettings.KEY_SHORTCUTS)?.isVisible =
            appShortcutManager.isDynamicShortcutsAvailable()
        findPreference<TwoStatePreference>(AppSettings.KEY_PROTECT_APP)
            ?.isChecked = !settings.appPassword.isNullOrEmpty()
        findPreference<ListPreference>(AppSettings.KEY_SCREENSHOTS_POLICY)?.run {
            entryValues = ScreenshotsPolicy.entries.names()
            setDefaultValueCompat(ScreenshotsPolicy.ALLOW.name)
        }
        findPreference<MultiSelectListPreference>(AppSettings.KEY_SEARCH_SUGGESTION_TYPES)?.let { pref ->
            pref.entryValues = SearchSuggestionType.entries.names()
            pref.entries = SearchSuggestionType.entries.map { pref.context.getString(it.titleResId) }.toTypedArray()
            pref.summaryProvider = MultiSummaryProvider(R.string.none)
            pref.values = settings.searchSuggestionTypes.mapToSet { it.name }
        }
        bindNavSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        settings.subscribe(this)
    }

    override fun onDestroyView() {
        settings.unsubscribe(this)
        super.onDestroyView()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        when (key) {
            AppSettings.KEY_THEME -> {
                AppCompatDelegate.setDefaultNightMode(settings.theme)
            }

            AppSettings.KEY_COLOR_THEME,
            AppSettings.KEY_THEME_AMOLED,
                -> {
                postRestart()
            }

            AppSettings.KEY_APP_LOCALE -> {
                AppCompatDelegate.setApplicationLocales(settings.appLocales)
            }

            AppSettings.KEY_NAV_MAIN -> {
                bindNavSummary()
            }

            AppSettings.KEY_APP_PASSWORD -> {
                findPreference<TwoStatePreference>(AppSettings.KEY_PROTECT_APP)
                    ?.isChecked = !settings.appPassword.isNullOrEmpty()
            }
        }
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        return when (preference.key) {
            AppSettings.KEY_PROTECT_APP -> {
                val pref = (preference as? TwoStatePreference ?: return false)
                if (pref.isChecked) {
                    pref.isChecked = false
                    startActivity(Intent(preference.context, ProtectSetupActivity::class.java))
                } else {
                    settings.appPassword = null
                }
                true
            }

            else -> super.onPreferenceTreeClick(preference)
        }
    }

    private fun postRestart() {
        viewLifecycleOwner.lifecycle.postDelayed(400) {
            activityRecreationHandle.recreateAll()
        }
    }

    private fun initLocalePicker(preference: ListPreference) {
        val locales = preference.context.getLocalesConfig()
            .toList()
            .sortedWithSafe(LocaleComparator())
        preference.entries = Array(locales.size + 1) { i ->
            if (i == 0) {
                getString(R.string.follow_system)
            } else {
                val lc = locales[i - 1]
                lc.getDisplayName(lc).toTitleCase(lc)
            }
        }
        preference.entryValues = Array(locales.size + 1) { i ->
            if (i == 0) {
                ""
            } else {
                locales[i - 1].toLanguageTag()
            }
        }
    }

    private fun bindNavSummary() {
        val pref = findPreference<Preference>(AppSettings.KEY_NAV_MAIN) ?: return
        pref.summary = settings.mainNavItems.joinToString {
            getString(it.title)
        }
    }
}
