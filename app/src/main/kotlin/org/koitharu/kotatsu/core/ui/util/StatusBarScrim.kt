package org.koitharu.kotatsu.core.ui.util

import kotlin.math.pow

/**
 * Shared shape of the status bar protection scrim (main screen + manga details): a `colorSurface`
 * fade that holds strong across the bar itself and then loses its effect on the way down.
 *
 * [BAR_STOPS] are the requested strengths, spread across the top [BAR_FRACTION] of the scrim (the part
 * that covers the status bar); the rest is the tail that carries the last value down to nothing.
 *
 * Two separate things make a fade end in a visible line, and both have bitten this scrim:
 *
 * 1. **Amplitude.** A tail is only invisible if it leaves from a value close to nothing already —
 *    over half a bar height, anything above ~0.3 alpha still reads as a band no matter how smooth the
 *    curve is. So the strong part of the fade lives entirely over the status bar (where there is
 *    content to protect) and the tail is only a soft landing.
 * 2. **Curvature.** The eye picks out a *curvature* discontinuity, not just a value one, so "reaches
 *    zero smoothly" is not enough — the rate of change has to reach zero too, or a Mach band appears.
 *    Shapes that failed here: a linear ramp (constant slope into zero), a raised cosine raised to a
 *    fractional power (turns the tail into u^1.2, whose curvature goes to infinity at the bottom
 *    edge), and piecewise-*linear* interpolation between [BAR_STOPS] (a slope kink at every stop).
 *
 * So the bar section is a monotone cubic Hermite (Fritsch–Carlson tangents — smooth through the
 * control points, and guaranteed not to overshoot between them), and the tail is
 * `(1-v)³·(a + b·v)`: the cubed factor forces value, slope *and* curvature to zero at the bottom,
 * while `b` is solved so the tail leaves at exactly the slope the Hermite arrives with.
 *
 * ponytail: plain gradient, no RenderEffect blur — works on minSdk 26 and has no RenderNode/EGL
 * hazards. Evenly spaced stops because GradientDrawable's offset-aware setColors is API 29.
 */
object StatusBarScrim {

	/** Status bar plus half a bar height of tail below it. */
	const val HEIGHT_FACTOR = 1.5f
	const val BAR_FRACTION = 1f / HEIGHT_FACTOR

	/**
	 * Strength across the status bar, from its top edge to its bottom edge. The last value is where
	 * the tail starts, so it has to be *faint*: a short tail leaving from a strong value reads as an
	 * edge no matter how smooth the curve is. Most of the drop happens over the bar, where there is
	 * content to protect, not below it.
	 */
	private val BAR_STOPS = floatArrayOf(0.97f, 0.93f, 0.74f, 0.28f)

	// Samples are interpolated linearly between each other, so each join is a small curvature step.
	// Dense enough that no join spans more than a pixel or two of a phone-sized scrim.
	private const val STOP_COUNT = 256

	/** Alpha (0..255) at each of [STOP_COUNT] evenly spaced positions down the scrim. */
	val alphas: FloatArray = FloatArray(STOP_COUNT) { i ->
		255f * alphaAt(i / (STOP_COUNT - 1f))
	}

	private fun alphaAt(t: Float): Float {
		val segments = BAR_STOPS.size - 1
		val span = BAR_FRACTION / segments
		if (t <= BAR_FRACTION) {
			val i = (t / span).toInt().coerceAtMost(segments - 1)
			val s = (t - i * span) / span
			return hermite(
				p0 = BAR_STOPS[i],
				p1 = BAR_STOPS[i + 1],
				m0 = tangent(i, span) * span,
				m1 = tangent(i + 1, span) * span,
				s = s,
			)
		}
		val tailSpan = 1f - BAR_FRACTION
		val v = (t - BAR_FRACTION) / tailSpan
		val a = BAR_STOPS.last()
		val b = tangent(segments, span) * tailSpan + 3f * a
		return (1f - v).pow(3) * (a + b * v)
	}

	/** Fritsch–Carlson tangent at control point [i]: the average of the neighbouring secant slopes. */
	private fun tangent(i: Int, span: Float): Float {
		val secant = { k: Int -> (BAR_STOPS[k + 1] - BAR_STOPS[k]) / span }
		return when (i) {
			0 -> secant(0)
			BAR_STOPS.lastIndex -> secant(BAR_STOPS.size - 2)
			else -> (secant(i - 1) + secant(i)) / 2f
		}
	}

	private fun hermite(p0: Float, p1: Float, m0: Float, m1: Float, s: Float): Float {
		val s2 = s * s
		val s3 = s2 * s
		return (2f * s3 - 3f * s2 + 1f) * p0 +
			(s3 - 2f * s2 + s) * m0 +
			(-2f * s3 + 3f * s2) * p1 +
			(s3 - s2) * m1
	}
}
