package org.haziffe.dropsauce.local.data

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PageCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FaviconCache
