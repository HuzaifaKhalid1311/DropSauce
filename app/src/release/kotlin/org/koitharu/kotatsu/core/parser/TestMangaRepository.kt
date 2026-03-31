package org.haziffe.dropsauce.core.parser

import org.haziffe.dropsauce.core.cache.MemoryContentCache
import org.haziffe.dropsauce.core.model.TestMangaSource
import org.haziffe.dropsauce.parsers.MangaLoaderContext

@Suppress("unused")
class TestMangaRepository(
	private val loaderContext: MangaLoaderContext,
	cache: MemoryContentCache
) : EmptyMangaRepository(TestMangaSource)
