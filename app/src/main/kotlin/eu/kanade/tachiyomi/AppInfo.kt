package eu.kanade.tachiyomi

import org.koitharu.kotatsu.BuildConfig

/**
 * Provides host application info for extensions.
 *
 * @since extension-lib 1.3
 */
object AppInfo {
	fun getVersionCode(): Int = BuildConfig.VERSION_CODE
	fun getVersionName(): String = BuildConfig.VERSION_NAME
}
