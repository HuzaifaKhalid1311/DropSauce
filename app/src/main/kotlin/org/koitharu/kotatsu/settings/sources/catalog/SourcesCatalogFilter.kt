package org.haziffe.dropsauce.settings.sources.catalog

import org.haziffe.dropsauce.parsers.model.ContentType

data class SourcesCatalogFilter(
	val mode: SourcesCatalogMode,
	val types: Set<ContentType>,
	val locale: String?,
	val isNewOnly: Boolean,
)
