package org.haziffe.dropsauce.browser

interface BrowserCallback : OnHistoryChangedListener {

	fun onLoadingStateChanged(isLoading: Boolean)

	fun onTitleChanged(title: CharSequence, subtitle: CharSequence?)
}
