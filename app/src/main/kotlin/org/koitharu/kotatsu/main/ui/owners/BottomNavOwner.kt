package org.koitharu.kotatsu.main.ui.owners

import android.view.View
import org.koitharu.kotatsu.core.ui.widgets.SlidingBottomNavigationView

interface BottomNavOwner {

	val bottomNav: SlidingBottomNavigationView?

	/** A still view at the top of the nav bar's resting spot, so snackbars never end up under the bar. */
	val snackbarAnchor: View?
}
