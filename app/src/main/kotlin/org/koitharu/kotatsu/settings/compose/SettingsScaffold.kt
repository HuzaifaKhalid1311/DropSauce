package org.koitharu.kotatsu.settings.compose

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.settings.SettingsActivity

/**
 * Window-Y based scroll request used by settings-search highlighting. The scaffold provides an
 * implementation; a highlighted [SettingsItem] passes its own window Y so the scaffold scrolls it
 * comfortably into view. (We do NOT use bringIntoViewRequester — it proved unreliable here.)
 */
val LocalSettingsHighlightScroll = compositionLocalOf<(Float) -> Unit> { {} }
val LocalSettingsScrollToTop = compositionLocalOf<(Float) -> Unit> { {} }

/**
 * Top-level container for every redesigned settings screen.
 *
 * Uses a plain scrolling [Column] (not a LazyColumn) so EVERY row is composed and laid out even
 * when off-screen — that is what lets settings-search reliably scroll to and highlight an option
 * anywhere on the page. Settings screens are short enough that non-lazy composition is fine.
 */
@Composable
fun SettingsScaffold(
	modifier: Modifier = Modifier,
	content: SettingsListScope.() -> Unit,
) {
	val scope = SettingsListScope()
	scope.content()

	val scrollState = rememberScrollState()
	val activity = LocalContext.current.findSettingsActivity()
	LaunchedEffect(activity, scrollState) {
		activity?.appBar?.setExpanded(scrollState.value == 0, false)
	}
	val coroutineScope = rememberCoroutineScope()
	val viewportTop = remember { mutableFloatStateOf(0f) }
	val viewportHeight = remember { mutableIntStateOf(0) }
	val scrollTo = remember(scrollState, coroutineScope) {
		{ windowY: Float ->
			val bias = viewportHeight.intValue * 0.28f
			val target = (scrollState.value + (windowY - viewportTop.floatValue) - bias)
				.toInt()
				.coerceIn(0, scrollState.maxValue)
			coroutineScope.launch { scrollState.animateScrollTo(target) }
			Unit
		}
	}
	val scrollToTop = remember(scrollState, coroutineScope) {
		{ windowY: Float ->
			val target = (scrollState.value + windowY - viewportTop.floatValue)
				.toInt().coerceIn(0, scrollState.maxValue)
			coroutineScope.launch { scrollState.animateScrollTo(target) }
			Unit
		}
	}

	Box(
		modifier = modifier
			.fillMaxSize()
			.nestedScroll(rememberNestedScrollInteropConnection())
			.onGloballyPositioned {
				viewportTop.floatValue = it.positionInWindow().y
				viewportHeight.intValue = it.size.height
			},
	) {
		CompositionLocalProvider(
			LocalSettingsHighlightScroll provides scrollTo,
			LocalSettingsScrollToTop provides scrollToTop,
		) {
			Column(
				modifier = Modifier
					.fillMaxSize()
					.verticalScroll(scrollState)
					.padding(top = 8.dp, bottom = 24.dp, start = 16.dp, end = 16.dp),
			) {
				scope.items.forEach { item ->
					Box(Modifier.fillMaxWidth()) { item() }
				}
			}
		}
	}
}

private tailrec fun Context.findSettingsActivity(): SettingsActivity? = when (this) {
	is SettingsActivity -> this
	is ContextWrapper -> baseContext.findSettingsActivity()
	else -> null
}

/** Minimal builder mirroring the old `LazyListScope.item { }` call sites used across screens. */
class SettingsListScope {
	internal val items = mutableListOf<@Composable () -> Unit>()
	fun item(content: @Composable () -> Unit) {
		items += content
	}
}
