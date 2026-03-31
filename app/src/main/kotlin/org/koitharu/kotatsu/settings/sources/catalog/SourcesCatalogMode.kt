package org.haziffe.dropsauce.settings.sources.catalog

import androidx.annotation.StringRes
import org.haziffe.dropsauce.R

enum class SourcesCatalogMode(
	@StringRes val titleResId: Int,
) {
	BUILTIN(R.string.source_mode_builtin),
	MIHON(R.string.source_mode_mihon),
}
