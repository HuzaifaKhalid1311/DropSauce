package org.koitharu.kotatsu.core.prefs

import android.content.Context
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.core.content.edit
import okhttp3.HttpUrl
import org.koitharu.kotatsu.core.util.ext.getEnumValue
import org.koitharu.kotatsu.core.util.ext.putEnumValue
import org.koitharu.kotatsu.core.util.ext.sanitizeHeaderValue
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.ifNullOrEmpty
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import java.io.File

class SourceSettings(context: Context, source: MangaSource) : MangaSourceConfig {

	private val prefs = context.getSharedPreferences(getStorageName(source.name), Context.MODE_PRIVATE)

	init {
		migrateLegacyStorageIfNeeded(context, source.name)
	}

	var defaultSortOrder: SortOrder?
		get() = prefs.getEnumValue(KEY_SORT_ORDER, SortOrder::class.java)
		set(value) = prefs.edit { putEnumValue(KEY_SORT_ORDER, value) }

	// Remembers a Mihon source's last-selected sort (a "srt@…" filter tag) so it survives app restarts.
	// The native [defaultSortOrder] above only covers built-in SortOrders, not the dynamic FilterList sort.
	var lastSortTagKey: String?
		get() = prefs.getString(KEY_LAST_SORT_KEY, null)
		set(value) = prefs.edit { putString(KEY_LAST_SORT_KEY, value) }

	var lastSortTagTitle: String?
		get() = prefs.getString(KEY_LAST_SORT_TITLE, null)
		set(value) = prefs.edit { putString(KEY_LAST_SORT_TITLE, value) }


	val isSlowdownEnabled: Boolean
		get() = prefs.getBoolean(KEY_SLOWDOWN, false)

	val isCaptchaNotificationsDisabled: Boolean
		get() = prefs.getBoolean(KEY_NO_CAPTCHA, false)

	var isInterceptCloudflareEnabled: Boolean
		get() = prefs.getBoolean(KEY_INTERCEPT_CLOUDFLARE, false)
		set(value) = prefs.edit { putBoolean(KEY_INTERCEPT_CLOUDFLARE, value) }

	@Suppress("UNCHECKED_CAST")
	override fun <T> get(key: ConfigKey<T>): T {
		return when (key) {
			is ConfigKey.UserAgent -> prefs.getString(key.key, key.defaultValue)
				.ifNullOrEmpty { key.defaultValue }
				.sanitizeHeaderValue()

			is ConfigKey.Domain -> prefs.getString(key.key, key.defaultValue)
				?.trim()
				?.takeIf(::isValidDomain)
				?: key.defaultValue

			is ConfigKey.ShowSuspiciousContent -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.SplitByTranslations -> prefs.getBoolean(key.key, key.defaultValue)
			is ConfigKey.PreferredImageServer -> prefs.getString(key.key, key.defaultValue)?.nullIfEmpty()
		} as T
	}

	operator fun <T> set(key: ConfigKey<T>, value: T) = prefs.edit {
		when (key) {
			is ConfigKey.Domain -> putString(key.key, value as String?)
			is ConfigKey.ShowSuspiciousContent -> putBoolean(key.key, value as Boolean)
			is ConfigKey.UserAgent -> putString(key.key, (value as String?)?.sanitizeHeaderValue())
			is ConfigKey.SplitByTranslations -> putBoolean(key.key, value as Boolean)
			is ConfigKey.PreferredImageServer -> putString(key.key, value as String? ?: "")
		}
	}

	fun subscribe(listener: OnSharedPreferenceChangeListener) {
		prefs.registerOnSharedPreferenceChangeListener(listener)
	}

	fun unsubscribe(listener: OnSharedPreferenceChangeListener) {
		prefs.unregisterOnSharedPreferenceChangeListener(listener)
	}

	companion object {

		private fun isValidDomain(value: String): Boolean = runCatching {
			require(value.isNotEmpty())
			val parts = value.split(':')
			require(parts.size <= 2)
			val urlBuilder = HttpUrl.Builder()
			urlBuilder.host(parts.first())
			if (parts.size == 2) {
				urlBuilder.port(parts[1].toInt())
			}
		}.isSuccess

		fun getStorageName(sourceName: String): String {
			val sourceId = sourceName
				.takeIf { it.startsWith("MIHON_") }
				?.removePrefix("MIHON_")
				?.substringBefore(':')
				?.toLongOrNull()
			return if (sourceId != null && sourceId > 0) {
				"source_$sourceId"
			} else {
				sourceName.replace(File.separatorChar, '$')
			}
		}

		private fun migrateLegacyStorageIfNeeded(context: Context, sourceName: String) {
			val canonicalName = getStorageName(sourceName)
			val legacyName = sourceName.replace(File.separatorChar, '$')
			if (canonicalName == legacyName) return
			val canonicalPrefs = context.getSharedPreferences(canonicalName, Context.MODE_PRIVATE)
			if (canonicalPrefs.all.isNotEmpty()) return
			val legacyPrefs = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
			if (legacyPrefs.all.isEmpty()) return
			canonicalPrefs.edit {
				legacyPrefs.all.forEach { (key, value) ->
					when (value) {
						is String -> putString(key, value)
						is Int -> putInt(key, value)
						is Long -> putLong(key, value)
						is Float -> putFloat(key, value)
						is Boolean -> putBoolean(key, value)
						is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
					}
				}
			}
		}

		const val KEY_NO_CAPTCHA = "no_captcha"
		const val KEY_SLOWDOWN = "slowdown"
		const val KEY_INTERCEPT_CLOUDFLARE = "intercept_cloudflare"
		const val KEY_SORT_ORDER = "sort_order"
		// Versioned key: bumping it drops every remembered server-side sort once, so all extensions
		// start on the in-app "Popular" listing after the update.
		const val KEY_LAST_SORT_KEY = "last_sort_key_v2"
		const val KEY_LAST_SORT_TITLE = "last_sort_title_v2"
	}
}
