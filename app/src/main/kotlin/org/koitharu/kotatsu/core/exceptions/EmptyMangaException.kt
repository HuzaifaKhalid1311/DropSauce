package org.haziffe.dropsauce.core.exceptions

import org.haziffe.dropsauce.details.ui.pager.EmptyMangaReason
import org.haziffe.dropsauce.parsers.model.Manga

class EmptyMangaException(
    val reason: EmptyMangaReason?,
    val manga: Manga,
    cause: Throwable?
) : IllegalStateException(cause)
