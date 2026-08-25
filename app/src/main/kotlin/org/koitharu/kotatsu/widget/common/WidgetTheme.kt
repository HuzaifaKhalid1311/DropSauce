package org.koitharu.kotatsu.widget.common

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.ColorRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.color.MaterialColors
import org.koitharu.kotatsu.R
import com.google.android.material.R as materialR

/**
 * The Material colors of the user's currently selected in-app theme, so widgets look like the
 * app instead of the fixed palette baked into their layouts.
 *
 * Tinting a shaped background from a widget requires `setBackgroundTintList`, which only became
 * remotable in Android 12 — below that the layouts keep their static colors.
 * ponytail: no pre-12 fallback; drop the version check if minSdk ever reaches 31.
 */
class WidgetColors(
	val surfaceContainer: Int,
	val surfaceContainerHigh: Int,
	val surfaceContainerHighest: Int,
	val primary: Int,
	val primaryContainer: Int,
	val onPrimary: Int,
	val onPrimaryContainer: Int,
	val onSurface: Int,
	val onSurfaceVariant: Int,
)

object WidgetTheme {

	private const val TAG = "WidgetTheme"

	// One step of elevation above pure black, so cards and rows stay distinguishable in AMOLED.
	private val AMOLED_CONTAINER_HIGH = Color.rgb(18, 18, 18)
	private val AMOLED_CONTAINER_HIGHEST = Color.rgb(30, 30, 30)

	// Building a themed context allocates a whole Resources object, and widgets rebuild on every
	// history/stats write. The result only depends on these three settings, so resolve once and
	// reuse until one of them changes.
	@Volatile
	private var cache: Pair<String, WidgetColors>? = null

	val isSupported: Boolean
		get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

	fun colors(context: Context): WidgetColors? {
		if (!isSupported) {
			return null
		}
		val settings = context.widgetEntryPoint().settings
		val isNight = isNight(context, settings.theme)
		val isAmoled = isNight && settings.isAmoledTheme
		val key = "${settings.colorScheme.name}/$isNight/$isAmoled"
		cache?.let { (cachedKey, cached) ->
			if (cachedKey == key) {
				return cached
			}
		}
		val colors = runCatching {
			resolve(context, isNight, isAmoled)
		}.onFailure {
			Log.e(TAG, "unable to resolve widget colors", it)
		}.getOrNull() ?: return null
		cache = key to colors
		return colors
	}

	/** Called when the user changes the theme, so the next widget update re-resolves. */
	fun invalidate() {
		cache = null
	}

	private fun resolve(context: Context, isNight: Boolean, isAmoled: Boolean): WidgetColors {
		val themed = themedContext(context, isNight, isAmoled)
		fun color(attr: Int, @ColorRes fallback: Int) =
			MaterialColors.getColor(themed, attr, ContextCompat.getColor(themed, fallback))
		// The app's AMOLED overlay only blackens `colorSurface`/`colorBackground`, which widgets
		// never use — so derive the container shades here instead of silently ignoring the setting.
		return WidgetColors(
			surfaceContainer = if (isAmoled) {
				Color.BLACK
			} else {
				color(materialR.attr.colorSurfaceContainer, R.color.kotatsu_surfaceContainer)
			},
			surfaceContainerHigh = if (isAmoled) {
				AMOLED_CONTAINER_HIGH
			} else {
				color(materialR.attr.colorSurfaceContainerHigh, R.color.kotatsu_surfaceContainerHigh)
			},
			surfaceContainerHighest = if (isAmoled) {
				AMOLED_CONTAINER_HIGHEST
			} else {
				color(materialR.attr.colorSurfaceContainerHighest, R.color.kotatsu_surfaceContainerHighest)
			},
			primary = color(androidx.appcompat.R.attr.colorPrimary, R.color.kotatsu_primary),
			primaryContainer = color(materialR.attr.colorPrimaryContainer, R.color.kotatsu_primaryContainer),
			onPrimary = color(materialR.attr.colorOnPrimary, R.color.kotatsu_onPrimary),
			onPrimaryContainer = color(materialR.attr.colorOnPrimaryContainer, R.color.kotatsu_onPrimaryContainer),
			onSurface = color(materialR.attr.colorOnSurface, R.color.kotatsu_onSurface),
			onSurfaceVariant = color(materialR.attr.colorOnSurfaceVariant, R.color.kotatsu_onSurfaceVariant),
		)
	}

	/**
	 * Applies every known widget view id at once: ids missing from this particular layout are
	 * silently skipped by RemoteViews, so one call covers all of them.
	 */
	@RequiresApi(Build.VERSION_CODES.S)
	fun apply(views: RemoteViews, colors: WidgetColors) {
		views.textColor(colors.onSurface, R.id.widget_header_title, R.id.widget_title, R.id.widget_stats_today_value)
		views.textColor(
			colors.onSurfaceVariant,
			R.id.widget_subtitle,
			R.id.widget_chapter,
			R.id.widget_stats_subtitle,
			R.id.widget_empty,
			R.id.widget_empty_hint,
			R.id.widget_empty_text,
		)
		views.textColor(colors.onPrimary, R.id.widget_cta_text)
		views.textColor(colors.onPrimaryContainer, R.id.widget_stats_chip)

		views.iconTint(colors.onSurface, R.id.widget_header_icon, R.id.widget_settings)
		views.iconTint(
			colors.onSurfaceVariant,
			R.id.widget_empty_icon,
			R.id.widget_placeholder_1,
			R.id.widget_placeholder_2,
			R.id.widget_placeholder_3,
		)
		views.iconTint(colors.onPrimary, R.id.widget_cta_icon, R.id.widget_play)

		views.backgroundTint(
			colors.surfaceContainer,
			R.id.widget_root,
			R.id.widget_stats_root,
			R.id.widget_continue_reading_empty_root,
		)
		views.backgroundTint(colors.surfaceContainerHigh, R.id.widget_item_body)
		views.backgroundTint(
			colors.surfaceContainerHighest,
			R.id.widget_cover,
			R.id.widget_cover_1,
			R.id.widget_cover_2,
			R.id.widget_cover_3,
		)
		// `widget_cta_icon` only carries a background in the compact layout; tinting a null
		// background elsewhere is a no-op.
		views.backgroundTint(colors.primary, R.id.widget_cta, R.id.widget_cta_icon, R.id.widget_play)
		views.backgroundTint(colors.primaryContainer, R.id.widget_stats_chip)

		views.setColorStateList(R.id.widget_progress, "setProgressTintList", ColorStateList.valueOf(colors.primary))
		views.setColorStateList(
			R.id.widget_progress,
			"setProgressBackgroundTintList",
			ColorStateList.valueOf(colors.surfaceContainerHighest),
		)
	}

	fun apply(context: Context, views: RemoteViews) {
		val colors = colors(context) ?: return
		apply(views, colors)
	}

	private fun isNight(context: Context, themeMode: Int): Boolean = when (themeMode) {
		AppCompatDelegate.MODE_NIGHT_YES -> true
		AppCompatDelegate.MODE_NIGHT_NO -> false
		else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
			Configuration.UI_MODE_NIGHT_YES
	}

	private fun themedContext(context: Context, isNight: Boolean, isAmoled: Boolean): Context {
		val settings = context.widgetEntryPoint().settings
		val config = Configuration(context.resources.configuration).apply {
			uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
				if (isNight) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
		}
		val wrapper = ContextThemeWrapper(context.createConfigurationContext(config), R.style.Theme_Kotatsu)
		wrapper.theme.applyStyle(settings.colorScheme.styleResId, true)
		if (isAmoled) {
			wrapper.theme.applyStyle(R.style.ThemeOverlay_Kotatsu_Amoled, true)
		}
		return wrapper
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private fun RemoteViews.textColor(color: Int, vararg ids: Int) {
		for (id in ids) setTextColor(id, color)
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private fun RemoteViews.iconTint(color: Int, vararg ids: Int) {
		val tint = ColorStateList.valueOf(color)
		for (id in ids) setColorStateList(id, "setImageTintList", tint)
	}

	@RequiresApi(Build.VERSION_CODES.S)
	private fun RemoteViews.backgroundTint(color: Int, vararg ids: Int) {
		val tint = ColorStateList.valueOf(color)
		for (id in ids) setColorStateList(id, "setBackgroundTintList", tint)
	}
}
