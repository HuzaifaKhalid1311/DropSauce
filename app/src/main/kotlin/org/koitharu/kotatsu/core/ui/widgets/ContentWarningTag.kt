package org.koitharu.kotatsu.core.ui.widgets

import android.content.res.ColorStateList
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import org.koitharu.kotatsu.core.model.ContentWarning

/** Binds a `Widget.Kotatsu.ContentWarningTag` view: colored text on a faint fill of the same color. */
fun TextView.bindContentWarning(warning: ContentWarning?) {
	isVisible = warning != null
	if (warning == null) {
		return
	}
	setText(warning.titleResId)
	val color = MaterialColors.harmonizeWithPrimary(context, ContextCompat.getColor(context, warning.colorResId))
	setTextColor(color)
	backgroundTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, TAG_FILL_ALPHA))
}

private const val TAG_FILL_ALPHA = 0x2E
