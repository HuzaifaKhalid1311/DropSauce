package org.koitharu.kotatsu.core.nav

import android.app.ActivityOptions
import android.os.Bundle
import android.view.View
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.isAnimationsEnabled
import org.koitharu.kotatsu.core.util.ext.isOnScreen

inline val FragmentActivity.router: AppRouter
	get() = AppRouter(this)

inline val Fragment.router: AppRouter
	get() = AppRouter(this)

tailrec fun Fragment.dismissParentDialog(): Boolean {
	return when (val parent = parentFragment) {
		null -> return false
		is DialogFragment -> {
			parent.dismiss()
			true
		}

		else -> parent.dismissParentDialog()
	}
}

/**
 * Closes the sheet hosting this fragment once its screen is covered - by the reader it just opened -
 * so Back lands on the page underneath instead of the sheet. Closing right away would flash that page
 * and the sheet's slide-out before the reader appears.
 */
fun Fragment.dismissParentSheetWhenCovered() {
	val sheet = generateSequence(parentFragment, Fragment::getParentFragment)
		.firstNotNullOfOrNull { it as? BaseAdaptiveSheet<*> } ?: return
	sheet.lifecycle.addObserver(object : DefaultLifecycleObserver {
		override fun onStop(owner: LifecycleOwner) {
			owner.lifecycle.removeObserver(this)
			sheet.dismissWithoutAnimation()
		}
	})
}

fun scaleUpActivityOptionsOf(view: View): Bundle? {
	if (!view.context.isAnimationsEnabled || !view.isOnScreen()) {
		return null
	}
	return ActivityOptions.makeScaleUpAnimation(
		/* source = */ view,
		/* startX = */ 0,
		/* startY = */ 0,
		/* width = */ view.width,
		/* height = */ view.height,
	).toBundle()
}
