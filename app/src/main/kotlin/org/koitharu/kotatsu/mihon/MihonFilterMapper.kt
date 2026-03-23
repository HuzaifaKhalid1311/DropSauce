package org.koitharu.kotatsu.mihon

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag

/**
 * Maps between Mihon FilterList and Yumemi's MangaListFilter/MangaTag system.
 *
 * Each Mihon filter is converted to a MangaTag with a structured key that encodes
 * the filter path, allowing round-trip conversion back to Mihon filter state.
 */
object MihonFilterMapper {

	private const val PREFIX_TOP = "top:"
	private const val PREFIX_SORT = "sort:"
	private const val PREFIX_TEXT = "text:"

	/**
	 * Convert a Mihon FilterList into Yumemi's MangaListFilterOptions.
	 */
	fun mapOptions(mihonFilters: FilterList, source: MangaSource): MangaListFilterOptions {
		val allTags = mutableSetOf<MangaTag>()
		var currentHeader = "General"

		mihonFilters.forEachIndexed { _, filter ->
			when (filter) {
				is Filter.Header -> {
					currentHeader = filter.name
				}
				is Filter.Separator -> { }
				is Filter.Group<*> -> {
					val state = filter.state
					if (state is List<*>) {
						state.forEach { subItem ->
							if (subItem is Filter<*>) {
								val tags = mapFilterToTags(subItem, filter.name, source)
								allTags.addAll(tags)
							}
						}
					}
				}
				else -> {
					val tags = mapFilterToTags(filter, null, source)
					allTags.addAll(tags)
				}
			}
		}

		return MangaListFilterOptions(
			availableTags = allTags,
		)
	}

	private fun mapFilterToTags(
		filter: Filter<*>,
		parentName: String?,
		source: MangaSource,
	): List<MangaTag> {
		val prefix = if (parentName != null) "$parentName/" else PREFIX_TOP

		return when (filter) {
			is Filter.CheckBox -> {
				listOf(MangaTag(title = filter.name, key = "$prefix${filter.name}", source = source))
			}
			is Filter.TriState -> {
				listOf(MangaTag(title = filter.name, key = "$prefix${filter.name}", source = source))
			}
			is Filter.Select<*> -> {
				filter.values.map { value ->
					val title = value.toString()
					MangaTag(title = title, key = "$prefix${filter.name}/$title", source = source)
				}
			}
			is Filter.Sort -> {
				filter.values.map { value ->
					MangaTag(title = value, key = "$PREFIX_SORT$prefix${filter.name}/$value", source = source)
				}
			}
			is Filter.Text -> {
				listOf(MangaTag(
					title = "📝 ${filter.name}",
					key = "$PREFIX_TEXT$prefix${filter.name}",
					source = source,
				))
			}
			is Filter.Group<*> -> {
				val nestedTags = mutableListOf<MangaTag>()
				(filter.state as? List<*>)?.forEach { subItem ->
					if (subItem is Filter<*>) {
						val nestedPrefix = if (parentName != null) "$parentName/${filter.name}" else filter.name
						nestedTags.addAll(mapFilterToTags(subItem, nestedPrefix, source))
					}
				}
				nestedTags
			}
			else -> emptyList()
		}
	}

	/**
	 * Update Mihon FilterList state based on selected/excluded tags from MangaListFilter.
	 */
	fun updateMihonFilters(mihonFilters: FilterList, kotoFilter: MangaListFilter) {
		val selectedTags = kotoFilter.tags.mapTo(mutableSetOf()) { it.key }
		val excludedTags = kotoFilter.tagsExclude.mapTo(mutableSetOf()) { it.key }

		mihonFilters.forEach { filter ->
			when (filter) {
				is Filter.Group<*> -> {
					(filter.state as? List<*>)?.forEach { subItem ->
						val sub = subItem as? Filter<*> ?: return@forEach
						updateSingleFilter(sub, filter.name, selectedTags, excludedTags)
					}
				}
				else -> {
					updateSingleFilter(filter, null, selectedTags, excludedTags)
				}
			}
		}
	}

	private fun updateSingleFilter(
		filter: Filter<*>,
		parentName: String?,
		selectedTags: Set<String>,
		excludedTags: Set<String>,
	) {
		val prefix = if (parentName != null) "$parentName/" else PREFIX_TOP
		when (filter) {
			is Filter.CheckBox -> {
				val key = "$prefix${filter.name}"
				filter.state = key in selectedTags
			}
			is Filter.TriState -> {
				val key = "$prefix${filter.name}"
				filter.state = when {
					key in selectedTags -> Filter.TriState.STATE_INCLUDE
					key in excludedTags -> Filter.TriState.STATE_EXCLUDE
					else -> Filter.TriState.STATE_IGNORE
				}
			}
			is Filter.Select<*> -> {
				filter.values.forEachIndexed { index, value ->
					val key = "$prefix${filter.name}/$value"
					if (key in selectedTags) {
						filter.state = index
					}
				}
			}
			is Filter.Sort -> {
				filter.values.forEachIndexed { index, value ->
					val key = "$PREFIX_SORT$prefix${filter.name}/$value"
					if (key in selectedTags) {
						filter.state = Filter.Sort.Selection(index, filter.state?.ascending ?: false)
					}
				}
			}
			is Filter.Text -> {
				val baseKey = "$PREFIX_TEXT$prefix${filter.name}"
				val matchingTag = selectedTags.find { it.startsWith(baseKey) }
				if (matchingTag != null) {
					val value = if (matchingTag.contains("=")) {
						matchingTag.substringAfter("=")
					} else {
						""
					}
					filter.state = value
				}
			}
			is Filter.Group<*> -> {
				(filter.state as? List<*>)?.forEach { subItem ->
					if (subItem is Filter<*>) {
						val nestedPrefix = if (parentName != null) "$parentName/${filter.name}" else filter.name
						updateSingleFilter(subItem, nestedPrefix, selectedTags, excludedTags)
					}
				}
			}
			is Filter.Header, is Filter.Separator -> { }
			else -> {}
		}
	}
}
