package org.haziffe.dropsauce.list.ui.model

import android.content.Context
import androidx.annotation.StringRes
import org.haziffe.dropsauce.core.model.getLocalizedTitle
import org.haziffe.dropsauce.core.ui.model.DateTimeAgo
import org.haziffe.dropsauce.parsers.model.MangaChapter

data class ListHeader private constructor(
	private val textRaw: Any,
	@StringRes val buttonTextRes: Int,
	val payload: Any?,
	val badge: String?,
	val filterMode: org.haziffe.dropsauce.explore.ui.SourceFilterMode? = null,
) : ListModel {

	constructor(
		text: CharSequence,
		@StringRes buttonTextRes: Int = 0,
		payload: Any? = null,
		badge: String? = null,
		filterMode: org.haziffe.dropsauce.explore.ui.SourceFilterMode? = null,
	) : this(textRaw = text, buttonTextRes, payload, badge, filterMode)

	constructor(
		@StringRes textRes: Int,
		@StringRes buttonTextRes: Int = 0,
		payload: Any? = null,
		badge: String? = null,
		filterMode: org.haziffe.dropsauce.explore.ui.SourceFilterMode? = null,
	) : this(textRaw = textRes, buttonTextRes, payload, badge, filterMode)

	constructor(
		chapter: MangaChapter,
		@StringRes buttonTextRes: Int = 0,
		payload: Any? = null,
		badge: String? = null,
		filterMode: org.haziffe.dropsauce.explore.ui.SourceFilterMode? = null,
	) : this(textRaw = chapter, buttonTextRes, payload, badge, filterMode)

	constructor(
		dateTimeAgo: DateTimeAgo,
		@StringRes buttonTextRes: Int = 0,
		payload: Any? = null,
		badge: String? = null,
		filterMode: org.haziffe.dropsauce.explore.ui.SourceFilterMode? = null,
	) : this(textRaw = dateTimeAgo, buttonTextRes, payload, badge, filterMode)

	fun getText(context: Context): CharSequence? = when (textRaw) {
		is CharSequence -> textRaw
		is Int -> if (textRaw != 0) context.getString(textRaw) else null
		is DateTimeAgo -> textRaw.format(context)
		is MangaChapter -> textRaw.getLocalizedTitle(context.resources)
		else -> null
	}

	override fun areItemsTheSame(other: ListModel): Boolean {
		return other is ListHeader && textRaw == other.textRaw
	}
}
