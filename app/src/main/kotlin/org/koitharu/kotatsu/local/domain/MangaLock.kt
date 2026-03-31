package org.haziffe.dropsauce.local.domain

import org.haziffe.dropsauce.core.util.MultiMutex
import org.haziffe.dropsauce.parsers.model.Manga
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MangaLock @Inject constructor() : MultiMutex<Manga>()
