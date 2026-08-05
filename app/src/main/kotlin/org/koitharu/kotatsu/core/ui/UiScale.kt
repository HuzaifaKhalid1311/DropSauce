package org.koitharu.kotatsu.core.ui

/**
 * Smallest width, in dp, that the layouts are designed against — the reference device. Deliberately
 * a couple of dp below its real width so rounding can never scale the reference device itself.
 */
private const val REFERENCE_WIDTH_DP = 424

/** A little below the system's smallest "Display size" step; further makes text uncomfortable. */
private const val MIN_AUTO_SCALE = 0.80f

/**
 * The automatic baseline scale for a screen [smallestScreenWidthDp] dp wide: 1f on the reference
 * device and on anything wider, and progressively smaller on narrower phones so they get the same
 * usable canvas the UI was designed for. Without it, rows and top bars that just fit on the
 * reference device overflow on a common 360dp phone.
 *
 * Applied by overriding the display density in [BaseActivity.attachBaseContext].
 */
internal fun autoUiScale(smallestScreenWidthDp: Int): Float {
	if (smallestScreenWidthDp <= 0) return 1f // unknown configuration - don't guess
	return (smallestScreenWidthDp / REFERENCE_WIDTH_DP.toFloat()).coerceIn(MIN_AUTO_SCALE, 1f)
}
