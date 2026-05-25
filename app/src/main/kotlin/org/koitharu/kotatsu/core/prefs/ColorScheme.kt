package org.koitharu.kotatsu.core.prefs

import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.parsers.util.find

@Keep
enum class ColorScheme(
	@StyleRes val styleResId: Int,
	@StringRes val titleResId: Int,
) {

	DEFAULT(R.style.ThemeOverlay_Kotatsu_Totoro, R.string.theme_name_totoro),
	MIKU(R.style.ThemeOverlay_Kotatsu_Miku, R.string.theme_name_miku),
	RENA(R.style.ThemeOverlay_Kotatsu_Asuka, R.string.theme_name_asuka),
	FROG(R.style.ThemeOverlay_Kotatsu_Mion, R.string.theme_name_mion),
	BLUEBERRY(R.style.ThemeOverlay_Kotatsu_Rikka, R.string.theme_name_rikka),
	SAKURA(R.style.ThemeOverlay_Kotatsu_Sakura, R.string.theme_name_sakura),
	MAMIMI(R.style.ThemeOverlay_Kotatsu_Mamimi, R.string.theme_name_mamimi),
	KANADE(R.style.ThemeOverlay_Kotatsu_Kanade, R.string.theme_name_kanade),
	ITSUKA(R.style.ThemeOverlay_Kotatsu_Itsuka, R.string.theme_name_itsuka),
	;

	companion object {

		val default: ColorScheme
			get() = DEFAULT

		fun getAvailableList(): List<ColorScheme> = ColorScheme.entries.toList()

		fun safeValueOf(name: String): ColorScheme? {
			return ColorScheme.entries.find(name)
		}
	}
}
