package org.koitharu.kotatsu.lnreader

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.lnreader.model.LnPlugin

/**
 * Bridges a plugin's `pluginSettings` declaration onto androidx [Preference] objects, so the existing
 * source-settings screen renders them with no novel-specific UI.
 *
 * Values must land in the very preferences file the JS `@libs/storage` shim reads — `ln_<id>_db`, each
 * wrapped in that shim's `{created, value, expires}` envelope. Hence every preference here is
 * non-persistent and writes through [write]: letting Preference persist raw values would store shapes
 * the plugin cannot read back.
 */
object LnPluginSettings {

	private const val TYPE_SWITCH = "Switch"
	private const val TYPE_SELECT = "Select"
	private const val TYPE_CHECKBOX_GROUP = "CheckboxGroup"

	fun storageName(pluginId: String): String = "ln_${pluginId}_db"

	fun hasSettings(plugin: LnPlugin): Boolean = !plugin.pluginSettings.isNullOrEmpty()

	fun buildPreferences(context: Context, plugin: LnPlugin): List<Preference> {
		val declaration = plugin.pluginSettings?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
			?: return emptyList()
		val prefs = context.getSharedPreferences(storageName(plugin.id), Context.MODE_PRIVATE)
		val result = ArrayList<Preference>(declaration.length())
		// Declaration order is the plugin's own order, which JSONObject preserves.
		for (key in declaration.keys()) {
			val setting = declaration.optJSONObject(key) ?: continue
			val label = setting.optString("label").ifEmpty { key }
			result += when (setting.optString("type")) {
				TYPE_SWITCH -> SwitchPreferenceCompat(context).apply {
					title = label
					isChecked = read(prefs, key) as? Boolean ?: setting.optBoolean("value")
					setOnPreferenceChangeListener { _, newValue ->
						write(prefs, key, newValue as Boolean)
						true
					}
				}

				TYPE_SELECT -> ListPreference(context).apply {
					val options = setting.optJSONArray("options").options()
					title = label
					entries = options.map { it.first }.toTypedArray()
					entryValues = options.map { it.second }.toTypedArray()
					value = read(prefs, key) as? String ?: setting.optString("value")
					summaryProvider = ListPreference.SimpleSummaryProvider.getInstance()
					setOnPreferenceChangeListener { _, newValue ->
						write(prefs, key, newValue as String)
						true
					}
				}

				TYPE_CHECKBOX_GROUP -> MultiSelectListPreference(context).apply {
					val options = setting.optJSONArray("options").options()
					title = label
					entries = options.map { it.first }.toTypedArray()
					entryValues = options.map { it.second }.toTypedArray()
					values = (read(prefs, key) as? JSONArray ?: setting.optJSONArray("value")).strings()
					setOnPreferenceChangeListener { _, newValue ->
						@Suppress("UNCHECKED_CAST")
						write(prefs, key, JSONArray().apply { (newValue as Set<String>).forEach(::put) })
						true
					}
				}

				// Text is the documented default — `type` is optional on a TextSetting.
				else -> EditTextPreference(context).apply {
					title = label
					text = read(prefs, key) as? String ?: setting.optString("value")
					summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
					setOnPreferenceChangeListener { _, newValue ->
						write(prefs, key, newValue as String)
						true
					}
				}
			}.apply {
				this.key = key
				// No PreferenceManager is attached, so Preference.shouldPersist() is false anyway; this
				// is belt-and-braces against a raw value ever reaching the plugin's storage file.
				isPersistent = false
			}
		}
		return result
	}

	private fun read(prefs: SharedPreferences, key: String): Any? {
		val stored = prefs.getString(key, null) ?: return null
		val envelope = runCatching { JSONObject(stored) }.getOrNull() ?: return null
		return envelope.opt("value")?.takeUnless { it == JSONObject.NULL }
	}

	private fun write(prefs: SharedPreferences, key: String, value: Any) {
		val envelope = JSONObject()
			.put("created", System.currentTimeMillis())
			.put("value", value)
			.put("expires", JSONObject.NULL)
		prefs.edit().putString(key, envelope.toString()).apply()
	}

	/** `[{label, value}, …]` → label/value pairs, tolerating a plain string array. */
	private fun JSONArray?.options(): List<Pair<String, String>> {
		if (this == null) return emptyList()
		return (0 until length()).map { i ->
			when (val option = opt(i)) {
				is JSONObject -> option.optString("label").ifEmpty { option.optString("value") } to
					option.optString("value")

				else -> option.toString() to option.toString()
			}
		}
	}

	private fun JSONArray?.strings(): Set<String> {
		if (this == null) return emptySet()
		return (0 until length()).mapTo(LinkedHashSet()) { optString(it) }
	}
}
