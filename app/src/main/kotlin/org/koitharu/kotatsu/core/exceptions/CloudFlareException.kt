package org.haziffe.dropsauce.core.exceptions

import okio.IOException
import org.haziffe.dropsauce.parsers.model.MangaSource

abstract class CloudFlareException(
	message: String,
	val state: Int,
) : IOException(message) {

	abstract val url: String

	abstract val source: MangaSource
}
