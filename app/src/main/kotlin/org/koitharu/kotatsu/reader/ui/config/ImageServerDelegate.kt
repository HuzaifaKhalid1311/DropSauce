package org.koitharu.kotatsu.reader.ui.config

import android.content.Context

class ImageServerDelegate {

	fun isAvailable(): Boolean = false

	fun getValue(): String? = null

	fun showDialog(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = false
}
