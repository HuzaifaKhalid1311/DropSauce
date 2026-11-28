package org.koitharu.kotatsu.history.ui

import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.recyclerview.widget.RecyclerView
import coil3.ImageLoader
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegate
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.list.ui.adapter.QuickFilterClickListener
import org.koitharu.kotatsu.list.ui.compose.ExpressiveQuickFilterRow
import org.koitharu.kotatsu.list.ui.compose.measureFilterChipHeightPx
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.QuickFilter
import org.koitharu.kotatsu.settings.compose.DropSauceTheme
import kotlin.math.roundToInt

/**
 * History's quick filters: wraps the shared [ExpressiveQuickFilterRow] in a fixed-height [ComposeView]
 * row inside History's [RecyclerView].
 */
fun historyQuickFilterAD(
	listener: QuickFilterClickListener,
	imageLoader: ImageLoader,
) = adapterDelegate<QuickFilter, ListModel>(
	layout = 0,
	layoutInflater = { parent, _ ->
		val res = parent.resources
		val chipHeightPx = measureFilterChipHeightPx(parent.context)
		val topPadPx = (2f * res.displayMetrics.density).roundToInt()
		val bottomPadPx = (6f * res.displayMetrics.density).roundToInt()
		ComposeView(parent.context).apply {
			tag = chipHeightPx
			layoutParams = RecyclerView.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				chipHeightPx + topPadPx + bottomPadPx,
			)
		}
	},
) {
	val chipHeightPx = itemView.tag as Int
	var chips by mutableStateOf<List<ChipsView.ChipModel>>(emptyList())
	(itemView as ComposeView).setContent {
		DropSauceTheme {
			val outerPad = dimensionResource(R.dimen.grid_spacing_outer)
			ExpressiveQuickFilterRow(
				chips = chips,
				imageLoader = imageLoader,
				onFilterOptionClick = listener::onFilterOptionClick,
				startPadding = outerPad,
				endPadding = outerPad,
				topPadding = 2.dp,
				bottomPadding = 6.dp,
				chipHeightPx = chipHeightPx,
			)
		}
	}
	bind {
		chips = item.items
	}
}
