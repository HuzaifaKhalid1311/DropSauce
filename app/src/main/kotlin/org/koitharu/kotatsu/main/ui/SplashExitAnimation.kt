package org.koitharu.kotatsu.main.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.window.SplashScreenView
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.graphics.withScale
import androidx.core.view.children
import androidx.core.splashscreen.SplashScreenViewProvider
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

private const val EXIT_MS = 750L

// how far cookie and logo shrink before the cookie opens up
private const val SQUISH = 0.82f

// how long after creation the system's copy of the splash can still turn up
private const val COVER_WAIT_MS = 3000L

// how long before the intro ends the portal goes under the system's copy (the intro has settled by then)
private const val COVER_LEAD_MS = 50L

// drawable/avd_splash geometry (from its generator script): a 1600-unit icon with a 9-lobe cookie
private const val ICON_UNITS = 1600f
private const val LOBES = 9
private const val POINTS = LOBES * 8
private const val COOKIE_RADIUS = 455f
private const val COOKIE_WAVE = 28f

private val standard = PathInterpolator(0.2f, 0f, 0f, 1f)
private val emphasizedDecelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
private val emphasizedAccelerate = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

/**
 * The intro backwards and inside out: the logo sinks back into the cookie, the cookie squishes, turns into
 * a window onto the app and spins out to the screen edges, smoothing into a circle. The system hands the
 * animated icon over as a surface that can't be faded or scaled, so the whole splash is swapped for
 * [SplashPortalDrawable], which starts from the intro's final frame. It starts once the app is ready and the
 * intro has played out (the intro is kept short so that rarely waits); the app takes touches throughout.
 *
 * Create it before the first frame and pass [play] as the exit listener.
 */
class SplashExit(private val activity: Activity, @StyleRes private val splashTheme: Int) : ViewTreeObserver.OnPreDrawListener {

	private val decor = activity.window.decorView as ViewGroup
	private var portal: SplashPortalDrawable? = null
	private val coverUntil = SystemClock.uptimeMillis() + COVER_WAIT_MS

	init {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			decor.viewTreeObserver.addOnPreDrawListener(this)
		}
	}

	/**
	 * Android 12+ shows its copy of the splash on its own for a few frames before calling [play], and the copy's
	 * icon doesn't always show in them, so the cookie blinked. The portal covers the copy from its first frame.
	 */
	override fun onPreDraw(): Boolean {
		val splash = decor.children.firstOrNull { it is SplashScreenView } as SplashScreenView?
		if (splash == null) {
			if (SystemClock.uptimeMillis() > coverUntil) {
				decor.viewTreeObserver.removeOnPreDrawListener(this)
			}
			return true
		}
		decor.viewTreeObserver.removeOnPreDrawListener(this)
		val icon = splash.iconView
		val introEnd = splash.iconAnimationStart?.plus(splash.iconAnimationDuration)?.toEpochMilli() ?: 0L
		// mid-intro the copy's icon is still animating, so the final frame would show around it
		if (icon != null && introEnd <= System.currentTimeMillis()) {
			portal = createPortal(splash, icon)
		}
		return true
	}

	fun play(provider: SplashScreenViewProvider) {
		decor.viewTreeObserver.removeOnPreDrawListener(this)
		// a fast start is ready mid-intro: let the system's copy finish it rather than jump to its last frame
		val introLeft = provider.iconAnimationStartMillis + provider.iconAnimationDurationMillis - System.currentTimeMillis()
		if (portal == null && introLeft > 0) {
			// the portal has to be on screen under the copy before the copy goes, or the screen blinks for a frame
			decor.postDelayed({ portal = createPortal(provider.view, provider.iconView) }, introLeft - COVER_LEAD_MS)
			decor.postDelayed({ exit(provider) }, max(introLeft, COVER_LEAD_MS))
		} else {
			exit(provider)
		}
	}

	private fun exit(provider: SplashScreenViewProvider) {
		val portal = portal ?: createPortal(provider.view, provider.iconView)
		provider.remove() // the system splash leaves in the frame the portal is already showing the same picture
		if (portal == null) {
			return
		}
		ValueAnimator.ofFloat(0f, EXIT_MS.toFloat()).apply {
			duration = EXIT_MS
			interpolator = LinearInterpolator()
			addUpdateListener { portal.time = it.animatedValue as Float }
			doOnEnd { decor.overlay.remove(portal) }
			start()
		}
	}

	private fun createPortal(splash: View, icon: View): SplashPortalDrawable? {
		val themed = ContextThemeWrapper(activity, splashTheme)
		val logo = ContextCompat.getDrawable(themed, R.drawable.splash_logo)?.mutate()
		if (logo == null || !decor.isAttachedToWindow || icon.width == 0) {
			return null
		}
		val iconPos = IntArray(2).also(icon::getLocationInWindow)
		// Android 12+ draws an animated icon at 1.5x its view, clipped to the view's mask (AdaptiveForegroundDrawable)
		val iconSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) icon.width * 1.5f else icon.width.toFloat()
		return SplashPortalDrawable(
			cx = iconPos[0] + icon.width / 2f,
			cy = iconPos[1] + icon.height / 2f,
			unit = iconSize / ICON_UNITS,
			background = (splash.background as? ColorDrawable)?.color
				?: themed.getThemeColor(androidx.core.splashscreen.R.attr.windowSplashScreenBackground),
			cookie = themed.getThemeColor(R.attr.splashShapeColor),
			logo = logo,
		).also {
			it.setBounds(0, 0, decor.width, decor.height)
			decor.overlay.add(it)
		}
	}
}

private class SplashPortalDrawable(
	private val cx: Float,
	private val cy: Float,
	private val unit: Float,
	@ColorInt private val background: Int,
	@ColorInt private val cookie: Int,
	private val logo: Drawable,
) : Drawable() {

	/** Milliseconds into the outro. */
	var time = 0f
		set(value) {
			field = value
			invalidateSelf()
		}

	private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
	private val hole = Path()
	private val frame = Path().apply { fillType = Path.FillType.EVEN_ODD }
	private val xs = FloatArray(POINTS)
	private val ys = FloatArray(POINTS)
	private var screenRadius = 0f

	init {
		val half = (ICON_UNITS / 2 * unit).toInt()
		logo.setBounds(cx.toInt() - half, cy.toInt() - half, cx.toInt() + half, cy.toInt() + half)
	}

	override fun onBoundsChange(bounds: Rect) {
		screenRadius = hypot(max(cx - bounds.left, bounds.right - cx), max(cy - bounds.top, bounds.bottom - cy))
	}

	override fun draw(canvas: Canvas) {
		// cookie and logo squish together to 0.82, then the cookie grows until even its smoothed-out edge clears the screen corners
		val squish = 1f - (1f - SQUISH) * standard.at(0f, 250f)
		val endScale = screenRadius / (COOKIE_RADIUS * unit) * 1.02f
		val scale = squish + (endScale - SQUISH) * standard.at(250f, 500f)
		val smooth = standard.at(250f, 350f)
		val rotation = PI.toFloat() / 4 * emphasizedDecelerate.at(150f, 550f)
		setCookie(COOKIE_RADIUS * unit * scale, COOKIE_WAVE * unit * scale * (1f - smooth), rotation)

		// the cookie fades out to show the app through it
		val cookieAlpha = 1f - standard.at(230f, 20f)
		if (cookieAlpha > 0f) {
			paint.color = cookie
			paint.alpha = (cookieAlpha * 255).toInt()
			canvas.drawPath(hole, paint)
		}
		// the logo sinks back in, the intro's 0.6 -> 1 the other way round
		val sink = emphasizedAccelerate.at(100f, 150f)
		if (sink < 1f) {
			logo.alpha = ((1f - sink) * 255).toInt()
			val logoScale = squish * (1f - 0.4f * sink)
			canvas.withScale(logoScale, logoScale, cx, cy) { logo.draw(this) }
		}
		frame.rewind()
		frame.addRect(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat(), Path.Direction.CW)
		frame.addPath(hole)
		paint.color = background
		canvas.drawPath(frame, paint)
	}

	/** The intro's cookie: Catmull-Rom through points on r = radius + wave * cos(9t), as cubic curves. */
	private fun setCookie(radius: Float, wave: Float, rotation: Float) {
		for (i in 0 until POINTS) {
			val t = 2 * PI.toFloat() * i / POINTS - PI.toFloat() / 2
			val r = radius + wave * cos(LOBES * (t + PI.toFloat() / 2))
			xs[i] = cx + r * cos(t + rotation)
			ys[i] = cy + r * sin(t + rotation)
		}
		hole.rewind()
		hole.moveTo(xs[0], ys[0])
		for (i in 0 until POINTS) {
			val p0 = (i + POINTS - 1) % POINTS
			val p2 = (i + 1) % POINTS
			val p3 = (i + 2) % POINTS
			hole.cubicTo(
				xs[i] + (xs[p2] - xs[p0]) / 6, ys[i] + (ys[p2] - ys[p0]) / 6,
				xs[p2] - (xs[p3] - xs[i]) / 6, ys[p2] - (ys[p3] - ys[i]) / 6,
				xs[p2], ys[p2],
			)
		}
		hole.close()
	}

	private fun Interpolator.at(start: Float, duration: Float) =
		getInterpolation(((time - start) / duration).coerceIn(0f, 1f))

	override fun setAlpha(alpha: Int) = Unit

	override fun setColorFilter(colorFilter: ColorFilter?) = Unit

	@Deprecated("Deprecated in Java")
	override fun getOpacity() = PixelFormat.TRANSLUCENT
}
