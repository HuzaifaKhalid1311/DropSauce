package org.haziffe.dropsauce.settings.utils

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.parsers.util.ifNullOrEmpty

class EditTextDefaultSummaryProvider(
	private val defaultValue: String,
) : Preference.SummaryProvider<EditTextPreference> {

	override fun provideSummary(
		preference: EditTextPreference,
	): CharSequence = preference.text.ifNullOrEmpty {
		preference.context.getString(R.string.default_s, defaultValue)
	}
}
