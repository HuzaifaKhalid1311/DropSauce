package org.haziffe.dropsauce.core.exceptions

class SyncApiException(
	message: String,
	val code: Int,
) : RuntimeException(message)
