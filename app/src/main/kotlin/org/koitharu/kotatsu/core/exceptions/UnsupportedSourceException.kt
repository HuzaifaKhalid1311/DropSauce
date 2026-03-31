package org.haziffe.dropsauce.core.exceptions

import org.haziffe.dropsauce.parsers.model.Manga

class UnsupportedSourceException(
	message: String?,
	val manga: Manga?,
) : IllegalArgumentException(message)
