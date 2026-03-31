package org.haziffe.dropsauce.list.ui.size

import android.view.View
import android.widget.TextView
import org.haziffe.dropsauce.history.ui.util.ReadingProgressView

interface ItemSizeResolver {

	val cellWidth: Int

	fun attachToView(
		view: View,
		textView: TextView?,
		progressView: ReadingProgressView?,
	)
}
