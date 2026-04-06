package org.koitharu.kotatsu.settings.search

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.XmlRes
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import androidx.preference.get
import dagger.Reusable
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.LocalizedAppContext
import org.koitharu.kotatsu.settings.AppearanceSettingsFragment
import org.koitharu.kotatsu.settings.DownloadsSettingsFragment
import org.koitharu.kotatsu.settings.ProxySettingsFragment
import org.koitharu.kotatsu.settings.ReaderSettingsFragment
import org.koitharu.kotatsu.settings.ServicesSettingsFragment
import org.koitharu.kotatsu.settings.StorageAndNetworkSettingsFragment
import org.koitharu.kotatsu.settings.SuggestionsSettingsFragment
import org.koitharu.kotatsu.settings.about.AboutSettingsFragment
import org.koitharu.kotatsu.settings.discord.DiscordSettingsFragment
import org.koitharu.kotatsu.settings.sources.ExtensionsSettingsFragment
import org.koitharu.kotatsu.settings.tracker.TrackerSettingsFragment
import org.koitharu.kotatsu.settings.userdata.storage.DataCleanupSettingsFragment
import javax.inject.Inject

@Reusable
@SuppressLint("RestrictedApi")
class SettingsSearchHelper @Inject constructor(
    @LocalizedAppContext private val context: Context,
) {

    fun inflatePreferences(): List<SettingsItem> {
        val preferenceManager = PreferenceManager(context)
        val result = ArrayList<SettingsItem>()
        preferenceManager.inflateTo(result, R.xml.pref_appearance, emptyList(), AppearanceSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_extensions, emptyList(), ExtensionsSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_reader, emptyList(), ReaderSettingsFragment::class.java)
        preferenceManager.inflateTo(
            result,
            R.xml.pref_network_storage,
            emptyList(),
            StorageAndNetworkSettingsFragment::class.java,
        )
        preferenceManager.inflateTo(
            result,
            R.xml.pref_data_cleanup,
            listOf(context.getString(R.string.storage_and_network)),
            DataCleanupSettingsFragment::class.java,
        )
        preferenceManager.inflateTo(result, R.xml.pref_downloads, emptyList(), DownloadsSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_tracker, emptyList(), TrackerSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_services, emptyList(), ServicesSettingsFragment::class.java)
        preferenceManager.inflateTo(result, R.xml.pref_about, emptyList(), AboutSettingsFragment::class.java)
        preferenceManager.inflateTo(
            result,
            R.xml.pref_proxy,
            listOf(context.getString(R.string.storage_and_network)),
            ProxySettingsFragment::class.java,
        )
        preferenceManager.inflateTo(
            result,
            R.xml.pref_suggestions,
            listOf(context.getString(R.string.services)),
            SuggestionsSettingsFragment::class.java,
        )
        preferenceManager.inflateTo(
            result,
            R.xml.pref_discord,
            listOf(context.getString(R.string.services)),
            DiscordSettingsFragment::class.java,
        )
        return result
    }

    private fun PreferenceManager.inflateTo(
        result: MutableList<SettingsItem>,
        @XmlRes resId: Int,
        breadcrumbs: List<String>,
        fragmentClass: Class<out PreferenceFragmentCompat>
    ) {
        val screen = inflateFromResource(context, resId, null)
        val screenTitle = screen.title?.toString()
        screen.inflateTo(
            result = result,
            breadcrumbs = if (screenTitle.isNullOrEmpty()) breadcrumbs else breadcrumbs + screenTitle,
            fragmentClass = fragmentClass,
        )
    }

    private fun PreferenceGroup.inflateTo(
        result: MutableList<SettingsItem>,
        breadcrumbs: List<String>,
        fragmentClass: Class<out PreferenceFragmentCompat>
    ): Unit = repeat(preferenceCount) { i ->
        val pref = this[i]
        if (pref is PreferenceGroup) {
            val screenTitle = pref.title?.toString()
            pref.inflateTo(
                result = result,
                breadcrumbs = if (screenTitle.isNullOrEmpty()) breadcrumbs else breadcrumbs + screenTitle,
                fragmentClass = fragmentClass,
            )
        } else {
            val key = pref.key ?: return@repeat
            val title = pref.title ?: return@repeat
            val summary = pref.summary?.toString().orEmpty()
            val searchText = buildString {
                append(title)
                append(' ')
                append(key)
                if (summary.isNotBlank()) {
                    append(' ')
                    append(summary)
                }
                if (breadcrumbs.isNotEmpty()) {
                    append(' ')
                    append(breadcrumbs.joinToString(" "))
                }
            }
            result.add(
                SettingsItem(
                    key = key,
                    title = title,
                    breadcrumbs = breadcrumbs,
                    searchText = searchText,
                    fragmentClass = fragmentClass,
                ),
            )
        }
    }
}
