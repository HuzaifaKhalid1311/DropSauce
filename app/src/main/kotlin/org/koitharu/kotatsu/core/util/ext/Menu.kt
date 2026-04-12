package org.koitharu.kotatsu.core.util.ext

import android.view.Menu
import java.lang.reflect.Method

fun Menu.setOptionalIconsVisibleCompat(isVisible: Boolean) {
	findOptionalIconsMethod()?.let { method ->
		runCatching { method.invoke(this, isVisible) }
	}
}

private fun Menu.findOptionalIconsMethod(): Method? {
	var currentClass: Class<*>? = javaClass
	while (currentClass != null) {
		val method = runCatching {
			currentClass.getDeclaredMethod("setOptionalIconsVisible", Boolean::class.javaPrimitiveType)
		}.getOrNull()
		if (method != null) {
			method.isAccessible = true
			return method
		}
		currentClass = currentClass.superclass
	}
	return null
}
