package org.koitharu.kotatsu.settings.reader

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.Context
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.viewModels
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.view.WindowInsetsCompat
import dagger.hilt.android.AndroidEntryPoint
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveOptionGroupItem
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog
import org.koitharu.kotatsu.core.ui.dialog.showConfirmDialog
import org.koitharu.kotatsu.core.util.ext.consumeAllSystemBarsInsets
import org.koitharu.kotatsu.core.util.ext.findKeyByValue
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.systemBarsInsets
import org.koitharu.kotatsu.databinding.ActivityReaderTapActionsBinding
import org.koitharu.kotatsu.reader.domain.TapGridArea
import org.koitharu.kotatsu.reader.ui.tapgrid.TapAction
import com.google.android.material.color.MaterialColors
import java.util.EnumMap

@AndroidEntryPoint
class ReaderTapGridConfigActivity : BaseActivity<ActivityReaderTapActionsBinding>(), View.OnClickListener,
	View.OnLongClickListener {

	private val viewModel: ReaderTapGridConfigViewModel by viewModels()

	private val controls = EnumMap<TapGridArea, TextView>(TapGridArea::class.java)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityReaderTapActionsBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		controls[TapGridArea.TOP_LEFT] = viewBinding.textViewTopLeft
		controls[TapGridArea.TOP_CENTER] = viewBinding.textViewTopCenter
		controls[TapGridArea.TOP_RIGHT] = viewBinding.textViewTopRight
		controls[TapGridArea.CENTER_LEFT] = viewBinding.textViewCenterLeft
		controls[TapGridArea.CENTER] = viewBinding.textViewCenter
		controls[TapGridArea.CENTER_RIGHT] = viewBinding.textViewCenterRight
		controls[TapGridArea.BOTTOM_LEFT] = viewBinding.textViewBottomLeft
		controls[TapGridArea.BOTTOM_CENTER] = viewBinding.textViewBottomCenter
		controls[TapGridArea.BOTTOM_RIGHT] = viewBinding.textViewBottomRight

		controls.forEach { (_, view) ->
			view.setOnClickListener(this)
			view.setOnLongClickListener(this)
		}

		viewModel.content.observe(this, ::onContentChanged)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.root.setPadding(
			barsInsets.left,
			barsInsets.top,
			barsInsets.right,
			barsInsets.bottom,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onCreateOptionsMenu(menu: Menu?): Boolean {
		menuInflater.inflate(R.menu.opt_tap_grid_config, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		return when (item.itemId) {
			R.id.action_reset -> {
				confirmReset()
				true
			}

			R.id.action_disable_all -> {
				viewModel.disableAll()
				true
			}

			else -> super.onOptionsItemSelected(item)
		}
	}

	override fun onClick(v: View) {
		val area = controls.findKeyByValue(v) ?: return
		showActionSelector(area, isLongTap = false)
	}

	override fun onLongClick(v: View?): Boolean {
		val area = controls.findKeyByValue(v) ?: return false
		showActionSelector(area, isLongTap = true)
		return true
	}

	private fun onContentChanged(content: Map<TapGridArea, ReaderTapGridConfigViewModel.TapActions>) {
		controls.forEach { (area, view) ->
			val actions = content[area]
			view.text = buildSpannedString {
				appendLine(getString(R.string.tap_action))
				bold {
					appendLine(actions?.tapAction.getText())
				}
				appendLine()
				appendLine(getString(R.string.long_tap_action))
				bold {
					appendLine(actions?.longTapAction.getText())
				}
			}
			view.background = createBackground(area, actions?.tapAction)
		}
	}

	// lint bug
	private fun TapAction?.getText(): String = if (this != null) {
		getString(nameStringResId)
	} else {
		getString(R.string.none)
	}

	private fun showActionSelector(area: TapGridArea, isLongTap: Boolean) {
		val selectedItem = viewModel.content.value[area]?.run {
			if (isLongTap) longTapAction else tapAction
		}?.ordinal ?: -1
		val names = listOf(getString(R.string.none)) + TapAction.entries.map { getString(it.nameStringResId) }
		showInstantChoiceDialog(
			context = this,
			icon = R.drawable.ic_tap,
			title = getString(if (isLongTap) R.string.long_tap_action else R.string.tap_action),
			options = names,
			selectedIndex = selectedItem + 1,
		) { which ->
			viewModel.setTapAction(area, isLongTap, TapAction.entries.getOrNull(which - 1))
		}
	}

	private fun confirmReset() {
		showConfirmDialog(
			context = this,
			icon = R.drawable.ic_revert,
			title = getString(R.string.reader_actions),
			message = getString(R.string.config_reset_confirm),
			confirmLabel = getString(R.string.reset),
			destructive = true,
		) {
			viewModel.reset()
		}
	}

	private fun createBackground(area: TapGridArea, action: TapAction?): Drawable {
		val r = 24f * resources.displayMetrics.density
		// Only the four corner cells are rounded, and only on their OUTER corner, so the whole
		// grid reads as a single rounded rectangle while the inner cells stay square.
		// cornerRadii order: top-left, top-right, bottom-right, bottom-left (x,y each).
		val radii = when (area) {
			TapGridArea.TOP_LEFT -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, 0f, 0f)
			TapGridArea.TOP_RIGHT -> floatArrayOf(0f, 0f, r, r, 0f, 0f, 0f, 0f)
			TapGridArea.BOTTOM_RIGHT -> floatArrayOf(0f, 0f, 0f, 0f, r, r, 0f, 0f)
			TapGridArea.BOTTOM_LEFT -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, r, r)
			else -> floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
		}
		val fillColor = if (action == null) {
			MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.TRANSPARENT)
		} else {
			ColorUtils.setAlphaComponent(action.color, 70)
		}
		val base = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadii = radii
			setColor(fillColor)
		}
		val mask = GradientDrawable().apply {
			shape = GradientDrawable.RECTANGLE
			cornerRadii = radii.clone()
			setColor(Color.WHITE)
		}
		val rippleColor = MaterialColors.getColor(this, android.R.attr.colorControlHighlight, Color.TRANSPARENT)
		return RippleDrawable(ColorStateList.valueOf(rippleColor), base, mask)
	}
}

/** Radio list that applies the tapped option immediately (no confirm button), like the old single-choice popup. */
private fun showInstantChoiceDialog(
	context: Context,
	@DrawableRes icon: Int,
	title: String,
	options: List<String>,
	selectedIndex: Int,
	onSelect: (Int) -> Unit,
) {
	showComposeDialog(context) { dismiss ->
		val colors = MaterialTheme.colorScheme
		ExpressiveDialogCard(
			icon = painterResource(icon),
			title = title,
			cornerAction = DialogCornerAction(
				icon = painterResource(R.drawable.ic_close),
				label = stringResource(android.R.string.cancel),
				onClick = dismiss,
			),
		) {
			options.forEachIndexed { index, option ->
				val isSelected = index == selectedIndex
				ExpressiveOptionGroupItem(
					index = index,
					count = options.size,
					onClick = {
						dismiss()
						onSelect(index)
					},
				) {
					RadioButton(selected = isSelected, onClick = null)
					Spacer(Modifier.width(12.dp))
					Text(
						text = option,
						style = MaterialTheme.typography.bodyLarge,
						fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
						color = if (isSelected) colors.primary else colors.onSurface,
						modifier = Modifier.weight(1f),
					)
				}
			}
		}
	}
}
