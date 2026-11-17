package org.koitharu.kotatsu.core.os

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.ColorScheme

/** Launcher icon choices; the themed ones take the app's color scheme. */
enum class AppIcon { DEFAULT, BLACK, MONO, THEME_DARK, THEME_LIGHT }

/**
 * Each icon is an `org.koitharu.kotatsu.icon.*` activity-alias of MainActivity in the manifest, and
 * exactly one of them is enabled — that is the only way Android lets an app change its launcher icon.
 */
object AppIconManager {

	private const val PREFIX = "org.koitharu.kotatsu.icon."

	/** Enables the alias for [icon] (in [scheme]'s colors when themed) and disables all the others. */
	fun apply(context: Context, icon: AppIcon, scheme: ColorScheme) {
		val target = aliasName(icon, scheme)
		val pm = context.packageManager
		// The new entry goes on before the old one goes off, so the launcher never sees the app without one.
		val names = listOf(target) + allAliases().filter { it != target }
		for (name in names) {
			val component = ComponentName(context, PREFIX + name)
			val shouldEnable = name == target
			val isEnabled = when (pm.getComponentEnabledSetting(component)) {
				PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
				PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == AppIcon.DEFAULT.name // the manifest default
				else -> false
			}
			if (shouldEnable != isEnabled) {
				pm.setComponentEnabledSetting(
					component,
					if (shouldEnable) {
						PackageManager.COMPONENT_ENABLED_STATE_ENABLED
					} else {
						PackageManager.COMPONENT_ENABLED_STATE_DISABLED
					},
					PackageManager.DONT_KILL_APP,
				)
			}
		}
	}

	/** The adaptive icon [icon] shows in [scheme], for previews. */
	fun iconRes(icon: AppIcon, scheme: ColorScheme): Int = when (icon) {
		AppIcon.DEFAULT -> R.mipmap.ic_launcher
		AppIcon.BLACK -> R.mipmap.ic_launcher_black
		AppIcon.MONO -> R.mipmap.ic_launcher_mono
		AppIcon.THEME_DARK -> when (scheme) {
			ColorScheme.DEFAULT -> R.mipmap.ic_launcher_default_dark
			ColorScheme.EXPRESSIVE -> R.mipmap.ic_launcher_expressive_dark
			ColorScheme.MIKU -> R.mipmap.ic_launcher_miku_dark
			ColorScheme.RENA -> R.mipmap.ic_launcher_rena_dark
			ColorScheme.FROG -> R.mipmap.ic_launcher_frog_dark
			ColorScheme.BLUEBERRY -> R.mipmap.ic_launcher_blueberry_dark
			ColorScheme.SAKURA -> R.mipmap.ic_launcher_sakura_dark
			ColorScheme.MAMIMI -> R.mipmap.ic_launcher_mamimi_dark
			ColorScheme.KANADE -> R.mipmap.ic_launcher_kanade_dark
			ColorScheme.ITSUKA -> R.mipmap.ic_launcher_itsuka_dark
			ColorScheme.SHANA -> R.mipmap.ic_launcher_shana_dark
			ColorScheme.LIME -> R.mipmap.ic_launcher_lime_dark
			ColorScheme.AMBER -> R.mipmap.ic_launcher_amber_dark
			ColorScheme.SKY -> R.mipmap.ic_launcher_sky_dark
		}

		AppIcon.THEME_LIGHT -> when (scheme) {
			ColorScheme.DEFAULT -> R.mipmap.ic_launcher_default_light
			ColorScheme.EXPRESSIVE -> R.mipmap.ic_launcher_expressive_light
			ColorScheme.MIKU -> R.mipmap.ic_launcher_miku_light
			ColorScheme.RENA -> R.mipmap.ic_launcher_rena_light
			ColorScheme.FROG -> R.mipmap.ic_launcher_frog_light
			ColorScheme.BLUEBERRY -> R.mipmap.ic_launcher_blueberry_light
			ColorScheme.SAKURA -> R.mipmap.ic_launcher_sakura_light
			ColorScheme.MAMIMI -> R.mipmap.ic_launcher_mamimi_light
			ColorScheme.KANADE -> R.mipmap.ic_launcher_kanade_light
			ColorScheme.ITSUKA -> R.mipmap.ic_launcher_itsuka_light
			ColorScheme.SHANA -> R.mipmap.ic_launcher_shana_light
			ColorScheme.LIME -> R.mipmap.ic_launcher_lime_light
			ColorScheme.AMBER -> R.mipmap.ic_launcher_amber_light
			ColorScheme.SKY -> R.mipmap.ic_launcher_sky_light
		}
	}

	private fun aliasName(icon: AppIcon, scheme: ColorScheme) = when (icon) {
		AppIcon.THEME_DARK -> "${scheme.name}_DARK"
		AppIcon.THEME_LIGHT -> "${scheme.name}_LIGHT"
		else -> icon.name
	}

	private fun allAliases(): List<String> = listOf(AppIcon.DEFAULT.name, AppIcon.BLACK.name, AppIcon.MONO.name) +
		ColorScheme.entries.flatMap { listOf("${it.name}_DARK", "${it.name}_LIGHT") }
}
