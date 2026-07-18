package org.koitharu.kotatsu.settings.compose

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment

/**
 * Common base for any Compose-hosted settings screen.
 *
 * - Provides the [ComposeOwnedScreen] marker so the activity (and the rest of the app) can
 *   identify Compose-driven settings fragments.
 * - Pushes the screen's title up to the host activity's MaterialToolbar in `onResume`,
 *   synchronously — no SideEffect race. This guarantees the title displayed in the
 *   activity's toolbar matches the current fragment by the time the user sees the frame
 *   after a back-pop.
 * - Provides [createComposeView] to eliminate the boilerplate ComposeView +
 *   ViewCompositionStrategy + DropSauceTheme setup across subclasses.
 */
abstract class BaseComposeSettingsFragment(
	@StringRes private val titleId: Int,
) : Fragment(), ComposeOwnedScreen {

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?,
	): View = createComposeView { Content() }

	@Composable
	protected open fun Content() = Unit

	protected fun createComposeView(content: @Composable () -> Unit): View {
		return ComposeView(requireContext()).apply {
			setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
			setContent {
				DropSauceTheme {
					content()
				}
			}
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		updateTitle()
	}

	override fun onStart() {
		super.onStart()
		updateTitle()
	}

	override fun onResume() {
		super.onResume()
		updateTitle()
	}

	private fun updateTitle() {
		if (titleId != 0) {
			activity?.setTitle(titleId)
		}
	}
}
