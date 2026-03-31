package org.haziffe.dropsauce.core.exceptions

import org.haziffe.dropsauce.core.model.UnknownMangaSource
import org.haziffe.dropsauce.parsers.model.MangaSource
import org.haziffe.dropsauce.parsers.network.CloudFlareHelper

class CloudFlareBlockedException(
	override val url: String,
	source: MangaSource?,
) : CloudFlareException("Blocked by CloudFlare", CloudFlareHelper.PROTECTION_BLOCKED) {

	override val source: MangaSource = source ?: UnknownMangaSource
}
