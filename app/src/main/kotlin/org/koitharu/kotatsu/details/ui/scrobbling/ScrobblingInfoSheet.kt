package org.koitharu.kotatsu.details.ui.scrobbling

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.activityViewModels
import coil3.ImageLoader
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.sheet.BaseAdaptiveSheet
import org.koitharu.kotatsu.core.util.ext.consume
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.databinding.SheetScrobblingBinding
import org.koitharu.kotatsu.details.ui.DetailsViewModel
import org.koitharu.kotatsu.scrobbling.common.domain.model.ScrobblingInfo
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import javax.inject.Inject

@AndroidEntryPoint
class ScrobblingInfoSheet : BaseAdaptiveSheet<SheetScrobblingBinding>() {

	@Inject
	lateinit var coil: ImageLoader

	private val viewModel by activityViewModels<DetailsViewModel>()
	private var scrobblerIndex: Int = -1

	private val bottomInset = mutableIntStateOf(0)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		scrobblerIndex = requireArguments().getInt(AppRouter.KEY_INDEX, scrobblerIndex)
	}

	override fun onCreateViewBinding(inflater: LayoutInflater, container: ViewGroup?): SheetScrobblingBinding {
		return SheetScrobblingBinding.inflate(inflater, container, false)
	}

	override fun onViewBindingCreated(binding: SheetScrobblingBinding, savedInstanceState: Bundle?) {
		super.onViewBindingCreated(binding, savedInstanceState)
		binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
		binding.composeView.setContent {
			DropSauceTheme {
				val density = LocalDensity.current
				val scrobblings by viewModel.scrobblingInfo.collectAsState()
				val info = scrobblings.getOrNull(scrobblerIndex)
				if (info != null) {
					ScrobblingInfoContent(
						info = info,
						imageLoader = coil,
						bottomInset = with(density) { bottomInset.intValue.toDp() },
						// Status is whatever the tracker last reported, so a rating change has to
						// carry it along or the update would clear it.
						onRatingChange = { rating ->
							viewModel.updateScrobbling(scrobblerIndex, rating, info.status)
						},
						onStatusChange = { status ->
							viewModel.updateScrobbling(scrobblerIndex, info.rating, status)
						},
						onCoverClick = { router.openImage(url = info.coverUrl, source = null) },
						onOpenInBrowser = { openInBrowser(info) },
						onEdit = { editBinding(info) },
						onUnregister = {
							viewModel.unregisterScrobbling(scrobblerIndex)
							dismiss()
						},
					)
				}
			}
		}
		// The sheet has nothing left to show once the binding is gone — including right after
		// unregistering from it.
		viewModel.scrobblingInfo.observe(viewLifecycleOwner) { scrobblings ->
			if (scrobblings.getOrNull(scrobblerIndex) == null) {
				dismissAllowingStateLoss()
			}
		}
		viewModel.onError.observeEvent(viewLifecycleOwner) {
			Toast.makeText(binding.root.context, it.getDisplayMessage(binding.root.resources), Toast.LENGTH_SHORT)
				.show()
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val typeMask = WindowInsetsCompat.Type.systemBars()
		bottomInset.intValue = insets.getInsets(typeMask).bottom
		return insets.consume(v, typeMask, bottom = true)
	}

	private fun openInBrowser(info: ScrobblingInfo) {
		if (!router.openExternalBrowser(info.externalUrl, getString(R.string.open_in_browser))) {
			Snackbar.make(
				viewBinding?.root ?: return,
				R.string.operation_not_supported,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	private fun editBinding(info: ScrobblingInfo) {
		val manga = viewModel.manga.value ?: return
		// Handed to the activity: this fragment is about to be gone and its own router would have
		// no fragment manager left to show the selector with.
		activity?.router?.showScrobblingSelectorSheet(manga, info.scrobbler)
		dismiss()
	}
}
