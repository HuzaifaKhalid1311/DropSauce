package org.koitharu.kotatsu.settings.sources

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.parser.EmptyMangaRepository
import org.koitharu.kotatsu.core.parser.MangaRepository

fun PreferenceFragmentCompat.addPreferencesFromRepository(repository: MangaRepository) = when (repository) {
	is EmptyMangaRepository -> addPreferencesFromEmptyRepository()
	else -> Unit
}

private fun PreferenceFragmentCompat.addPreferencesFromEmptyRepository() {
	val preference = Preference(requireContext())
	preference.setIcon(R.drawable.ic_alert_outline)
	preference.isPersistent = false
	preference.isSelectable = false
	preference.order = 200
	preference.setSummary(R.string.unsupported_source)
	preferenceScreen.addPreference(preference)
}
