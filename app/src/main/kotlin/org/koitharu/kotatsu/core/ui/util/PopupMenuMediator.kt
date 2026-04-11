package org.koitharu.kotatsu.core.ui.util

import android.graphics.drawable.InsetDrawable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuProvider
import org.koitharu.kotatsu.R

class PopupMenuMediator(
	private val provider: MenuProvider,
) : View.OnLongClickListener, View.OnContextClickListener, PopupMenu.OnMenuItemClickListener,
	PopupMenu.OnDismissListener {

	override fun onContextClick(v: View): Boolean = onLongClick(v)

	override fun onLongClick(v: View): Boolean {
		val menu = PopupMenu(v.context, v)
		(menu.menu as? MenuBuilder)?.setOptionalIconsVisible(true)
		provider.onCreateMenu(menu.menu, menu.menuInflater)
		provider.onPrepareMenu(menu.menu)
		if (!menu.menu.hasVisibleItems()) {
			return false
		}
		adjustMenuIconSpacing(menu.menu, v.resources.getDimensionPixelSize(R.dimen.menu_icon_text_spacing_extra))
		menu.setForceShowIcon(true)
		menu.setOnMenuItemClickListener(this)
		menu.setOnDismissListener(this)
		menu.show()
		return true
	}

	private fun adjustMenuIconSpacing(menu: Menu, endInset: Int) {
		for (index in 0 until menu.size()) {
			val item = menu.getItem(index)
			item.icon?.let { icon ->
				if (icon !is InsetDrawable) {
					item.icon = InsetDrawable(icon.mutate(), 0, 0, endInset, 0)
				}
			}
			item.subMenu?.let { adjustMenuIconSpacing(it, endInset) }
		}
	}

	override fun onMenuItemClick(item: MenuItem): Boolean {
		return provider.onMenuItemSelected(item)
	}

	override fun onDismiss(menu: PopupMenu) {
		provider.onMenuClosed(menu.menu)
	}

	fun attach(view: View) {
		view.setOnLongClickListener(this)
		view.setOnContextClickListener(this)
	}
}
