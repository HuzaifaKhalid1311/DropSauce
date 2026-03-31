package org.haziffe.dropsauce.core.exceptions

import okio.IOException
import org.haziffe.dropsauce.parsers.model.MangaSource

class InteractiveActionRequiredException(
	val source: MangaSource,
	val url: String,
	val userAgent: String? = null,
	val successCookieUrl: String? = null,
	val successCookieName: String? = null,
) : IOException("Interactive action is required for ${source.name}")
