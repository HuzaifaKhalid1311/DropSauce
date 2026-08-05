package org.koitharu.kotatsu.list.ui.model

import org.koitharu.kotatsu.R

/**
 * Heads-up that the app now picks its own scaling per screen, shown at the top of Favourites and
 * History. Both screens use this one key, so closing it on either dismisses it on both for good.
 */
const val TIP_UI_SCALING = "ui_scaling_note"

val uiScalingTip = TipModel(
	key = TIP_UI_SCALING,
	title = R.string.ui_scaling_tip_title,
	text = R.string.ui_scaling_tip,
	icon = R.drawable.ic_zoom_in,
	primaryButtonText = 0,
	secondaryButtonText = 0,
	isClosable = true,
)
