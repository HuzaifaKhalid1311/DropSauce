package org.haziffe.dropsauce.settings.appearance

import android.os.Bundle
import androidx.preference.ListPreference
import dagger.hilt.android.AndroidEntryPoint
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.prefs.AppSettings
import org.haziffe.dropsauce.core.prefs.DetailsUiMode
import org.haziffe.dropsauce.core.ui.BasePreferenceFragment
import org.haziffe.dropsauce.core.util.ext.setDefaultValueCompat
import org.haziffe.dropsauce.parsers.util.names
import org.haziffe.dropsauce.settings.utils.PercentSummaryProvider
import org.haziffe.dropsauce.settings.utils.SliderPreference

@AndroidEntryPoint
class PreviewSettingsFragment :
    BasePreferenceFragment(R.string.details_appearance) {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.pref_details_appearance)

        findPreference<ListPreference>(AppSettings.KEY_DETAILS_UI)?.run {
            entryValues = DetailsUiMode.entries.names()
            setDefaultValueCompat(DetailsUiMode.MODERN.name)
        }

        findPreference<SliderPreference>(AppSettings.KEY_DETAILS_BACKDROP_BLUR_AMOUNT)
            ?.summaryProvider = PercentSummaryProvider()
    }
}
