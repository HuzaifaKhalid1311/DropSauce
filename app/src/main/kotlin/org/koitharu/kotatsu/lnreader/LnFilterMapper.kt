package org.koitharu.kotatsu.lnreader

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.json.JSONArray
import org.json.JSONObject

/**
 * Translates an LNReader plugin's `filters` declaration into the vendored Mihon [Filter] tree, and
 * the user's choices back into the `{key: {type, value}}` object `popularNovels` expects.
 *
 * Going through [Filter] rather than inventing a novel-specific filter model means the whole dynamic
 * filter UI — sheet, sort picker, tag encoding, "filter applied" badge — is reused with zero changes.
 *
 * Filter order is the declaration order of the plugin's JSON object (Android's [JSONObject] keeps
 * insertion order), and that ordinal *is* the index path `MihonFilterMapper` encodes against, so the
 * two sides only agree as long as this walks the keys in the same order every time.
 */
object LnFilterMapper {

	private const val TYPE_TEXT = "Text"
	private const val TYPE_PICKER = "Picker"
	private const val TYPE_CHECKBOX = "Checkbox"
	private const val TYPE_SWITCH = "Switch"
	private const val TYPE_XCHECKBOX = "XCheckbox"

	/** Builds a fresh default filter list. Returns an empty list when the plugin declares none. */
	fun toFilterList(rawFilters: String?): FilterList {
		val root = rawFilters?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return FilterList()
		val filters = ArrayList<Filter<*>>()
		for (key in root.keys()) {
			val spec = root.optJSONObject(key) ?: continue
			val label = spec.optString("label").ifEmpty { key }
			val options = spec.optJSONArray("options").toOptions()
			when (spec.optString("type")) {
				TYPE_SWITCH -> filters += LnCheckBox(label, spec.optBoolean("value"))
				TYPE_TEXT -> filters += LnText(label, spec.optString("value"))
				TYPE_PICKER -> {
					val selected = options.indexOfFirst { it.value == spec.optString("value") }
					filters += LnSelect(label, options, selected.coerceAtLeast(0))
				}

				TYPE_CHECKBOX -> {
					val checked = spec.optJSONArray("value").toStringSet()
					filters += LnCheckBoxGroup(
						label,
						options,
						options.map { LnCheckBox(it.label, it.value in checked) },
					)
				}

				TYPE_XCHECKBOX -> {
					val value = spec.optJSONObject("value")
					val include = value?.optJSONArray("include").toStringSet()
					val exclude = value?.optJSONArray("exclude").toStringSet()
					filters += LnTriStateGroup(
						label,
						options,
						options.map { option ->
							LnTriState(
								option.label,
								when (option.value) {
									in include -> Filter.TriState.STATE_INCLUDE
									in exclude -> Filter.TriState.STATE_EXCLUDE
									else -> Filter.TriState.STATE_IGNORE
								},
							)
						},
					)
				}
			}
		}
		return FilterList(filters)
	}

	/**
	 * Reads [filters] back into the plugin's value object. Keys are re-derived from [rawFilters] in the
	 * same order [toFilterList] used, so a plugin whose filters changed between calls simply drops the
	 * mismatched entries rather than writing values under the wrong key.
	 */
	fun toValuesJson(rawFilters: String?, filters: FilterList): JSONObject? {
		val root = rawFilters?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
		val out = JSONObject()
		val keys = root.keys().asSequence().toList()
		val list = filters.toList()
		for ((index, key) in keys.withIndex()) {
			val spec = root.optJSONObject(key) ?: continue
			val type = spec.optString("type")
			val filter = list.getOrNull(index) ?: continue
			val value: Any = when {
				filter is LnCheckBox && type == TYPE_SWITCH -> filter.state
				filter is LnText && type == TYPE_TEXT -> filter.state
				filter is LnSelect && type == TYPE_PICKER ->
					filter.options.getOrNull(filter.state)?.value ?: continue

				filter is LnCheckBoxGroup && type == TYPE_CHECKBOX -> JSONArray().apply {
					filter.state.forEachIndexed { i, child ->
						if (child.state) put(filter.options[i].value)
					}
				}

				filter is LnTriStateGroup && type == TYPE_XCHECKBOX -> {
					val include = JSONArray()
					val exclude = JSONArray()
					filter.state.forEachIndexed { i, child ->
						when (child.state) {
							Filter.TriState.STATE_INCLUDE -> include.put(filter.options[i].value)
							Filter.TriState.STATE_EXCLUDE -> exclude.put(filter.options[i].value)
						}
					}
					JSONObject().put("include", include).put("exclude", exclude)
				}

				else -> continue
			}
			out.put(key, JSONObject().put("type", type).put("value", value))
		}
		return out.takeIf { it.length() > 0 }
	}

	private fun JSONArray?.toOptions(): List<LnOption> {
		if (this == null) return emptyList()
		return (0 until length()).mapNotNull { index ->
			val option = optJSONObject(index) ?: return@mapNotNull null
			val value = option.optString("value")
			LnOption(option.optString("label").ifEmpty { value }, value)
		}
	}

	private fun JSONArray?.toStringSet(): Set<String> {
		if (this == null) return emptySet()
		return (0 until length()).mapTo(HashSet()) { optString(it) }
	}

	data class LnOption(val label: String, val value: String)

	// The vendored Filter subtypes are abstract, so each needs a concrete stand-in. The group types
	// keep their options alongside so toValuesJson can map a child index back to its plugin value.
	private class LnCheckBox(name: String, state: Boolean) : Filter.CheckBox(name, state)

	private class LnText(name: String, state: String) : Filter.Text(name, state)

	private class LnTriState(name: String, state: Int) : Filter.TriState(name, state)

	private class LnSelect(
		name: String,
		val options: List<LnOption>,
		state: Int,
	) : Filter.Select<String>(name, options.map { it.label }.toTypedArray(), state)

	private class LnCheckBoxGroup(
		name: String,
		val options: List<LnOption>,
		state: List<LnCheckBox>,
	) : Filter.Group<LnCheckBox>(name, state)

	private class LnTriStateGroup(
		name: String,
		val options: List<LnOption>,
		state: List<LnTriState>,
	) : Filter.Group<LnTriState>(name, state)
}
