package org.koitharu.kotatsu.core.os

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ColorScheme
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import javax.inject.Inject

/**
 * Launcher icon choices, all following the app's light / dark / OLED look (Mono only has light and dark),
 * except Static: the dark default icon that never switches, for launchers that glitch on icon swaps.
 */
enum class AppIcon { DEFAULT, THEMED, MONO, STATIC }

/**
 * Each icon is an `org.koitharu.kotatsu.icon.*` activity-alias of MainActivity in the manifest, and
 * exactly one of them is enabled — that is the only way Android lets an app change its launcher icon.
 * Switching closes the app's task, so automatic switches wait until the app is in the background.
 */
object AppIconManager {

	private const val PREFIX = "org.koitharu.kotatsu.icon."

	/** Index into a light / dark / OLED triple. */
	private const val LIGHT = 0
	private const val DARK = 1
	private const val OLED = 2

	@Volatile
	private var appliedAlias: String? = null

	/** Themed icons per scheme, as light / dark / OLED. */
	private val THEMED = mapOf(
		ColorScheme.DEFAULT to intArrayOf(R.mipmap.ic_launcher_default_light, R.mipmap.ic_launcher_default_dark, R.mipmap.ic_launcher_default_oled),
		ColorScheme.EXPRESSIVE to intArrayOf(R.mipmap.ic_launcher_expressive_light, R.mipmap.ic_launcher_expressive_dark, R.mipmap.ic_launcher_expressive_oled),
		ColorScheme.MIKU to intArrayOf(R.mipmap.ic_launcher_miku_light, R.mipmap.ic_launcher_miku_dark, R.mipmap.ic_launcher_miku_oled),
		ColorScheme.RENA to intArrayOf(R.mipmap.ic_launcher_rena_light, R.mipmap.ic_launcher_rena_dark, R.mipmap.ic_launcher_rena_oled),
		ColorScheme.FROG to intArrayOf(R.mipmap.ic_launcher_frog_light, R.mipmap.ic_launcher_frog_dark, R.mipmap.ic_launcher_frog_oled),
		ColorScheme.BLUEBERRY to intArrayOf(R.mipmap.ic_launcher_blueberry_light, R.mipmap.ic_launcher_blueberry_dark, R.mipmap.ic_launcher_blueberry_oled),
		ColorScheme.SAKURA to intArrayOf(R.mipmap.ic_launcher_sakura_light, R.mipmap.ic_launcher_sakura_dark, R.mipmap.ic_launcher_sakura_oled),
		ColorScheme.MAMIMI to intArrayOf(R.mipmap.ic_launcher_mamimi_light, R.mipmap.ic_launcher_mamimi_dark, R.mipmap.ic_launcher_mamimi_oled),
		ColorScheme.KANADE to intArrayOf(R.mipmap.ic_launcher_kanade_light, R.mipmap.ic_launcher_kanade_dark, R.mipmap.ic_launcher_kanade_oled),
		ColorScheme.ITSUKA to intArrayOf(R.mipmap.ic_launcher_itsuka_light, R.mipmap.ic_launcher_itsuka_dark, R.mipmap.ic_launcher_itsuka_oled),
		ColorScheme.SHANA to intArrayOf(R.mipmap.ic_launcher_shana_light, R.mipmap.ic_launcher_shana_dark, R.mipmap.ic_launcher_shana_oled),
		ColorScheme.LIME to intArrayOf(R.mipmap.ic_launcher_lime_light, R.mipmap.ic_launcher_lime_dark, R.mipmap.ic_launcher_lime_oled),
		ColorScheme.AMBER to intArrayOf(R.mipmap.ic_launcher_amber_light, R.mipmap.ic_launcher_amber_dark, R.mipmap.ic_launcher_amber_oled),
		ColorScheme.SKY to intArrayOf(R.mipmap.ic_launcher_sky_light, R.mipmap.ic_launcher_sky_dark, R.mipmap.ic_launcher_sky_oled),
	)

	/** Enables the alias the current settings call for and disables all the others. Blocking; call off the main thread. */
	fun apply(context: Context, settings: AppSettings) {
		val target = aliasName(settings.appIcon, settings.colorScheme, mode(settings))
		if (target == appliedAlias) return
		// The new entry goes on before the old one goes off, so the launcher never sees the app without one.
		val names = listOf(target) + allAliases().filter { it != target }
		for (name in names) {
			val component = ComponentName(context, PREFIX + name)
			val shouldEnable = name == target
			if (shouldEnable != isEnabled(context, name)) {
				context.packageManager.setComponentEnabledSetting(
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
		appliedAlias = target
	}

	/** Whether the launcher entry the settings call for is already the enabled one, so nothing would switch. */
	fun isApplied(context: Context, settings: AppSettings): Boolean =
		isEnabled(context, aliasName(settings.appIcon, settings.colorScheme, mode(settings)))

	private fun isEnabled(context: Context, name: String): Boolean =
		when (context.packageManager.getComponentEnabledSetting(ComponentName(context, PREFIX + name))) {
			PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
			PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> name == AppIcon.DEFAULT.name // the manifest default
			else -> false
		}

	/** The adaptive icon [icon] gets in [scheme] and the given look, for previews. */
	fun iconRes(icon: AppIcon, scheme: ColorScheme, isDark: Boolean, isOled: Boolean): Int {
		val mode = mode(isDark, isOled)
		return when (icon) {
			AppIcon.DEFAULT -> intArrayOf(R.mipmap.ic_launcher_white, R.mipmap.ic_launcher, R.mipmap.ic_launcher_black)[mode]
			AppIcon.MONO -> if (mode == LIGHT) R.mipmap.ic_launcher_mono_light else R.mipmap.ic_launcher_mono
			AppIcon.THEMED -> THEMED.getValue(scheme)[mode]
			AppIcon.STATIC -> R.mipmap.ic_launcher
		}
	}

	/** The app's own look; when it follows the system, the system's night mode decides. */
	private fun mode(settings: AppSettings): Int {
		val isDark = when (settings.theme) {
			AppCompatDelegate.MODE_NIGHT_YES -> true
			AppCompatDelegate.MODE_NIGHT_NO -> false
			else -> Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
				Configuration.UI_MODE_NIGHT_YES
		}
		return mode(isDark, settings.isAmoledTheme)
	}

	private fun mode(isDark: Boolean, isOled: Boolean) = when {
		!isDark -> LIGHT
		isOled -> OLED
		else -> DARK
	}

	private fun aliasName(icon: AppIcon, scheme: ColorScheme, mode: Int) = when (icon) {
		AppIcon.DEFAULT -> arrayOf("DEFAULT_WHITE", "DEFAULT", "DEFAULT_BLACK")[mode]
		AppIcon.MONO -> if (mode == LIGHT) "MONO_LIGHT" else "MONO"
		AppIcon.THEMED -> "THEME_${scheme.name}_" + arrayOf("LIGHT", "DARK", "OLED")[mode]
		AppIcon.STATIC -> "DEFAULT"
	}

	private fun allAliases(): List<String> = listOf("DEFAULT", "DEFAULT_WHITE", "DEFAULT_BLACK", "MONO", "MONO_LIGHT") +
		ColorScheme.entries.flatMap { scheme -> listOf("LIGHT", "DARK", "OLED").map { "THEME_${scheme.name}_$it" } }
}

/**
 * After every app update, re-enables the launcher alias the settings call for. Component states
 * outlive updates, so an update that drops or renames the enabled alias would otherwise leave the
 * app with no launcher entry at all (Android then opens App info instead).
 */
@AndroidEntryPoint
class AppIconRepairReceiver : BroadcastReceiver() {

	@Inject
	lateinit var settings: AppSettings

	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
		val pending = goAsync()
		processLifecycleScope.launch(Dispatchers.Default) {
			try {
				AppIconManager.apply(context.applicationContext, settings)
			} finally {
				pending.finish()
			}
		}
	}
}
