package org.haziffe.dropsauce.core.ui.util

import androidx.annotation.StringRes

class ReversibleAction(
	@StringRes val stringResId: Int,
	val handle: ReversibleHandle?,
)
