package org.koitharu.kotatsu.core.util.ext

import android.content.res.Resources
import android.os.Build
import androidx.annotation.PluralsRes
import androidx.annotation.Px
import androidx.core.util.TypedValueCompat
import coil3.size.Size
import kotlin.math.roundToInt
import androidx.core.R as androidxR

@Px
fun Resources.resolveDp(dp: Int) = resolveDp(dp.toFloat()).roundToInt()

@Px
fun Resources.resolveDp(dp: Float) = TypedValueCompat.dpToPx(dp, displayMetrics)

fun Resources.getQuantityStringSafe(@PluralsRes resId: Int, quantity: Int, vararg formatArgs: Any): String = try {
	getQuantityString(resId, quantity, *formatArgs)
} catch (e: Resources.NotFoundException) {
	if (Build.VERSION.SDK_INT == Build.VERSION_CODES.VANILLA_ICE_CREAM) { // known issue
		e.printStackTraceDebug()
		formatArgs.firstOrNull()?.toString() ?: quantity.toString()
	} else {
		throw e
	}
}

fun Resources.getNotificationIconSize() = Size(
	getDimensionPixelSize(androidxR.dimen.compat_notification_large_icon_max_width),
	getDimensionPixelSize(androidxR.dimen.compat_notification_large_icon_max_height),
)
