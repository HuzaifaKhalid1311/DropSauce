package org.koitharu.kotatsu.core.image

import android.graphics.Bitmap

/** The bundled JPEG XL decoder (native/jxl, decoder-only libjxl): Android has none of its own. */
object JxlDecoder {

	init {
		System.loadLibrary("jxldec")
	}

	/** The first frame as an sRGB ARGB_8888 bitmap, or null if the data cannot be decoded. */
	external fun decode(bytes: ByteArray): Bitmap?
}
