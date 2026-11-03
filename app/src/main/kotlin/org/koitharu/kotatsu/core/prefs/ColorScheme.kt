package org.koitharu.kotatsu.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import com.google.android.material.color.DynamicColors
import org.koitharu.kotatsu.R

@Keep
enum class ColorScheme(
	@StyleRes val styleResId: Int,
	@StringRes val titleResId: Int,
	@StyleRes val splashThemeResId: Int,
) {

	DEFAULT(R.style.ThemeOverlay_Kotatsu_Totoro, R.string.theme_name_totoro, R.style.Theme_Kotatsu_Splash_Totoro),
	EXPRESSIVE(R.style.ThemeOverlay_Kotatsu_Expressive, R.string.theme_name_expressive, R.style.Theme_Kotatsu_Splash_Expressive),
	MIKU(R.style.ThemeOverlay_Kotatsu_Miku, R.string.theme_name_miku, R.style.Theme_Kotatsu_Splash_Miku),
	RENA(R.style.ThemeOverlay_Kotatsu_Asuka, R.string.theme_name_asuka, R.style.Theme_Kotatsu_Splash_Asuka),
	FROG(R.style.ThemeOverlay_Kotatsu_Mion, R.string.theme_name_mion, R.style.Theme_Kotatsu_Splash_Mion),
	BLUEBERRY(R.style.ThemeOverlay_Kotatsu_Rikka, R.string.theme_name_rikka, R.style.Theme_Kotatsu_Splash_Rikka),
	SAKURA(R.style.ThemeOverlay_Kotatsu_Sakura, R.string.theme_name_sakura, R.style.Theme_Kotatsu_Splash_Sakura),
	MAMIMI(R.style.ThemeOverlay_Kotatsu_Mamimi, R.string.theme_name_mamimi, R.style.Theme_Kotatsu_Splash_Mamimi),
	KANADE(R.style.ThemeOverlay_Kotatsu_Kanade, R.string.theme_name_kanade, R.style.Theme_Kotatsu_Splash_Kanade),
	ITSUKA(R.style.ThemeOverlay_Kotatsu_Itsuka, R.string.theme_name_itsuka, R.style.Theme_Kotatsu_Splash_Itsuka),
	SHANA(R.style.ThemeOverlay_Kotatsu_Shana, R.string.theme_name_shana, R.style.Theme_Kotatsu_Splash_Shana),
	LIME(R.style.ThemeOverlay_Kotatsu_Lime, R.string.theme_name_lime, R.style.Theme_Kotatsu_Splash_Lime),
	AMBER(R.style.ThemeOverlay_Kotatsu_Amber, R.string.theme_name_amber, R.style.Theme_Kotatsu_Splash_Amber),
	SKY(R.style.ThemeOverlay_Kotatsu_Sky, R.string.theme_name_sky, R.style.Theme_Kotatsu_Splash_Sky),
	;

	companion object {

		val default: ColorScheme
			get() = if (DynamicColors.isDynamicColorAvailable()) {
				EXPRESSIVE
			} else {
				DEFAULT
			}

		fun getAvailableList(): List<ColorScheme> {
			val list = ColorScheme.entries.toMutableList()
			if (!DynamicColors.isDynamicColorAvailable()) {
				list.remove(EXPRESSIVE)
			}
			return list
		}
	}
}
