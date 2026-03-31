package org.haziffe.dropsauce.list.ui.adapter

interface ListStateHolderListener {

	fun onRetryClick(error: Throwable)

	fun onSecondaryErrorActionClick(error: Throwable) = Unit

	fun onEmptyActionClick()

	fun onFooterButtonClick() = Unit
}
