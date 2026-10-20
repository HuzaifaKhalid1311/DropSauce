package org.koitharu.kotatsu.main.ui

import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import com.google.android.material.color.MaterialColors
import org.koitharu.kotatsu.core.ui.widgets.FloatingBottomNavigationView
import com.google.android.material.R as materialR

/**
 * Progressive blur + surface fade over the content behind the status bar and the floating nav pill,
 * ported from AirSync's ProgressiveBlurModifier. Zones are re-measured every frame, so the bottom one
 * shrinks away as the pill slides off on scroll. Android 13+ only (RuntimeShader).
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class BarBlur(
	private val content: View,
	private val bar: FloatingBottomNavigationView?,
) : ViewTreeObserver.OnPreDrawListener {

	private val shader = RuntimeShader(SHADER).apply {
		// Top matches the old status bar scrim (fades into the screen background), bottom matches AirSync.
		setColorUniform("topColor", Color.valueOf(color(materialR.attr.colorSurface)))
		setColorUniform("bottomColor", Color.valueOf(color(materialR.attr.colorSurfaceContainer)))
	}
	private var last = FloatArray(3) { Float.NaN }

	var isEnabled = false
		set(value) {
			if (field == value) return
			field = value
			if (value) {
				content.viewTreeObserver.addOnPreDrawListener(this)
				content.invalidate()
			} else {
				content.viewTreeObserver.removeOnPreDrawListener(this)
				content.setRenderEffect(null)
				last.fill(Float.NaN)
			}
		}

	/** Height of the top zone in px: the status bar plus a tail below it, 0 when the bar is hidden. */
	var statusBarZone = 0f
		set(value) {
			field = value
			content.invalidate()
		}

	override fun onPreDraw(): Boolean {
		val parent = content.parent as? View ?: return true
		// Screen edges, in the content view's own coordinates (it is offset by the collapsing appbar).
		val top = -(content.top + content.translationY)
		val bottom = parent.height + top
		val bottomZone = if (bar != null && bar.isFloating && bar.isShown) {
			(parent.height - bar.top - bar.translationY).coerceAtLeast(0f) * BOTTOM_HEIGHT_FACTOR
		} else {
			0f
		}
		if (top == last[0] && statusBarZone == last[1] && bottomZone == last[2]) return true
		last = floatArrayOf(top, statusBarZone, bottomZone)
		content.setRenderEffect(
			if (statusBarZone < 1f && bottomZone < 1f) {
				null
			} else {
				shader.setFloatUniform("top", top)
				shader.setFloatUniform("topHeight", statusBarZone)
				shader.setFloatUniform("bottom", bottom)
				shader.setFloatUniform("bottomHeight", bottomZone)
				RenderEffect.createRuntimeShaderEffect(shader, "content")
			},
		)
		return true
	}

	private fun color(attr: Int) = MaterialColors.getColor(content, attr, Color.TRANSPARENT)

	private companion object {

		/** Bottom zone height relative to the visible part of the pill (incl. the gesture area below it). */
		const val BOTTOM_HEIGHT_FACTOR = 1.6f

		val SHADER = """
			uniform shader content;
			uniform float top;
			uniform float topHeight;
			uniform float bottom;
			uniform float bottomHeight;
			layout(color) uniform half4 topColor;
			layout(color) uniform half4 bottomColor;

			const float BLUR_RADIUS = 40.0;
			// The status bar icons sit right on the top fade, so it stays stronger than AirSync's 0.65.
			const float TOP_ALPHA = 0.85;
			const float BOTTOM_ALPHA = 0.65;
			const int SAMPLES = 4;

			float zone(float distance, float height) {
				return height < 1.0 ? 0.0 : 1.0 - clamp(distance / height, 0.0, 1.0);
			}

			half4 main(float2 fragCoord) {
				float tTop = zone(fragCoord.y - top, topHeight);
				float tBottom = zone(bottom - fragCoord.y, bottomHeight);
				float t = max(tTop, tBottom);
				if (t <= 0.0) {
					return content.eval(fragCoord);
				}
				float radius = pow(t, 1.5) * BLUR_RADIUS;
				// Dithered jitter hides the sampling grid.
				float dither = fract(sin(dot(fragCoord, float2(12.9898, 78.233))) * 43758.5453);
				float2 jitter = float2(dither - 0.5, fract(dither * 1.618) - 0.5);
				float offsetScale = radius / float(SAMPLES);
				float radiusSq = radius * radius;
				half4 accum = half4(0.0);
				float weightSum = 0.0;
				for (int x = -SAMPLES; x <= SAMPLES; x++) {
					for (int y = -SAMPLES; y <= SAMPLES; y++) {
						float2 offset = (float2(float(x), float(y)) + jitter) * offsetScale;
						float distSq = dot(offset, offset);
						if (distSq <= radiusSq) {
							float weight = exp(-3.0 * distSq / radiusSq);
							accum += content.eval(fragCoord + offset) * weight;
							weightSum += weight;
						}
					}
				}
				half4 blurred = weightSum > 0.0 ? accum / weightSum : content.eval(fragCoord);
				return tTop >= tBottom
					? mix(blurred, topColor, tTop * TOP_ALPHA)
					: mix(blurred, bottomColor, tBottom * BOTTOM_ALPHA);
			}
		""".trimIndent()
	}
}
