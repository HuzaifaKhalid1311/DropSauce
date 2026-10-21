package org.koitharu.kotatsu.core.ui.util

import android.view.View
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.core.ui.menu.MenuPopup
import org.koitharu.kotatsu.core.util.ext.HapticEffect
import org.koitharu.kotatsu.core.util.ext.adjustPopupMenuIcons
import org.koitharu.kotatsu.core.util.ext.hapticFeedback
import org.koitharu.kotatsu.core.util.ext.setOptionalIconsVisibleCompat

class PopupMenuMediator(
	private val provider: MenuProvider,
) : View.OnLongClickListener, View.OnContextClickListener {

	override fun onContextClick(v: View): Boolean = onLongClick(v)

	override fun onLongClick(v: View): Boolean {
		val menu = MenuPopup(v)
		menu.menu.setOptionalIconsVisibleCompat(true)
		provider.onCreateMenu(menu.menu, menu.menuInflater)
		provider.onPrepareMenu(menu.menu)
		if (!menu.menu.hasVisibleItems()) {
			return false
		}
		menu.menu.adjustPopupMenuIcons(v.resources)
		v.hapticFeedback(HapticEffect.LONG_PRESS)
		menu.setForceShowIcon(true)
		menu.setOnMenuItemClickListener(provider::onMenuItemSelected)
		menu.setOnDismissListener { provider.onMenuClosed(menu.menu) }
		menu.show()
		return true
	}

	fun attach(view: View) {
		view.setOnLongClickListener(this)
		view.setOnContextClickListener(this)
	}
}
