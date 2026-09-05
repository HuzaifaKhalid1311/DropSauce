package org.koitharu.kotatsu.core.ui.dialog

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.findActivity
import org.koitharu.kotatsu.settings.compose.DropSauceTheme

/**
 * Hosts a Compose [content] inside a transparent [ComponentDialog], styled as the app's
 * M3 Expressive popup (mirrors [org.koitharu.kotatsu.reader.ui.showChapterJumpDialog]).
 * [content] receives a `dismiss` callback. Returns the dialog so callers can tweak cancel behavior.
 */
fun showComposeDialog(
	context: Context,
	cancelable: Boolean = true,
	onCancel: (() -> Unit)? = null,
	content: @Composable (dismiss: () -> Unit) -> Unit,
): ComponentDialog {
	val dialog = ComponentDialog(context)
	dialog.setContentView(
		ComposeView(context).apply {
			setContent {
				DropSauceTheme {
					content { dialog.dismiss() }
				}
			}
		},
	)
	dialog.setCancelable(cancelable)
	if (onCancel != null) {
		dialog.setOnCancelListener { onCancel() }
	}
	dialog.window?.run {
		setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
		setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
	}
	// plain dialog, not a DialogFragment — dismiss with the host to avoid a leaked window
	val owner = context.findActivity() as? LifecycleOwner
	if (owner != null) {
		val observer = object : DefaultLifecycleObserver {
			override fun onDestroy(owner: LifecycleOwner) = dialog.dismiss()
		}
		owner.lifecycle.addObserver(observer)
		dialog.setOnDismissListener { owner.lifecycle.removeObserver(observer) }
	}
	dialog.show()
	return dialog
}

/**
 * Rounded surface with a circular icon badge, headline title and optional supporting message,
 * followed by [content] (typically stacked [ExpressivePillButton]s). The building block shared by
 * every expressive dialog.
 */
@Composable
fun ExpressiveDialogCard(
	icon: Painter,
	title: String,
	message: String? = null,
	messageModifier: Modifier = Modifier,
	onCopyTitle: (() -> Unit)? = null,
	content: @Composable ColumnScope.() -> Unit,
) {
	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp),
		contentAlignment = Alignment.Center,
	) {
		Surface(
			shape = RoundedCornerShape(28.dp),
			color = MaterialTheme.colorScheme.surfaceContainerHigh,
			modifier = Modifier
				.fillMaxWidth()
				.widthIn(max = 400.dp),
		) {
			Box {
				Column(
					modifier = Modifier.padding(24.dp),
					horizontalAlignment = Alignment.CenterHorizontally,
				) {
					Box(
						modifier = Modifier
							.size(56.dp)
							.background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
						contentAlignment = Alignment.Center,
					) {
						Icon(
							painter = icon,
							contentDescription = null,
							tint = MaterialTheme.colorScheme.onSecondaryContainer,
							modifier = Modifier.size(28.dp),
						)
					}
					Spacer(Modifier.height(16.dp))
					Text(
						text = title,
						style = MaterialTheme.typography.headlineSmall,
						color = MaterialTheme.colorScheme.onSurface,
						textAlign = TextAlign.Center,
					)
					if (message != null) {
						Spacer(Modifier.height(8.dp))
						Box(
							modifier = messageModifier
								.heightIn(max = 260.dp)
								.fillMaxWidth()
								.verticalScroll(rememberScrollState()),
							contentAlignment = Alignment.Center,
						) {
							Text(
								text = message,
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
								textAlign = TextAlign.Center,
							)
						}
					}
					Spacer(Modifier.height(24.dp))
					content()
				}
				if (onCopyTitle != null) {
					IconButton(
						onClick = onCopyTitle,
						modifier = Modifier
							.align(Alignment.TopEnd)
							.padding(8.dp),
					) {
						Icon(
							painter = painterResource(R.drawable.ic_content_copy),
							contentDescription = stringResource(androidx.preference.R.string.copy),
							tint = MaterialTheme.colorScheme.onSurfaceVariant,
							modifier = Modifier.size(20.dp),
						)
					}
				}
			}
		}
	}
}

/** Full-width 56dp pill button, filled ([primary]) or tonal, with an optional leading icon. */
@Composable
fun ExpressivePillButton(
	text: String,
	modifier: Modifier = Modifier,
	icon: Painter? = null,
	primary: Boolean = true,
	enabled: Boolean = true,
	colors: ButtonColors? = null,
	onClick: () -> Unit,
) {
	val label: @Composable RowScope.() -> Unit = {
		if (icon != null) {
			Icon(painter = icon, contentDescription = null, modifier = Modifier.size(20.dp))
			Spacer(Modifier.size(8.dp))
		}
		Text(text = text, style = MaterialTheme.typography.labelLarge)
	}
	val btnModifier = modifier
		.fillMaxWidth()
		.height(56.dp)
	if (primary) {
		Button(
			onClick = onClick,
			enabled = enabled,
			shape = RoundedCornerShape(28.dp),
			colors = colors ?: ButtonDefaults.buttonColors(),
			modifier = btnModifier,
			content = label,
		)
	} else {
		FilledTonalButton(
			onClick = onClick,
			enabled = enabled,
			shape = RoundedCornerShape(28.dp),
			colors = colors ?: ButtonDefaults.filledTonalButtonColors(),
			modifier = btnModifier,
			content = label,
		)
	}
}

/** Low-emphasis full-width text button, for the "cancel"/"close" row. */
@Composable
fun ExpressiveDialogTextButton(
	text: String,
	onClick: () -> Unit,
) {
	TextButton(
		onClick = onClick,
		modifier = Modifier
			.fillMaxWidth()
			.height(48.dp),
	) {
		Text(text = text, style = MaterialTheme.typography.labelLarge)
	}
}

/** One selectable destination in [showActionChoiceDialog]. */
class DialogAction(
	val label: String,
	@param:DrawableRes val icon: Int? = null,
	val onClick: () -> Unit,
)

/**
 * Expressive "choose a destination" dialog: an icon badge, [title], optional [message], and a
 * vertical stack of [actions] (first filled, rest tonal), closed by a [dismissLabel] text button.
 * Selecting an action dismisses the dialog, then runs it — matching the old `setItems` behavior.
 */
fun showActionChoiceDialog(
	context: Context,
	@DrawableRes icon: Int,
	title: String,
	message: String? = null,
	actions: List<DialogAction>,
	dismissLabel: String,
	onCopyTitle: (() -> Unit)? = null,
) {
	showComposeDialog(context) { dismiss ->
		ExpressiveDialogCard(
			icon = painterResource(icon),
			title = title,
			message = message,
			onCopyTitle = onCopyTitle,
		) {
			actions.forEachIndexed { index, action ->
				if (index > 0) {
					Spacer(Modifier.height(8.dp))
				}
				ExpressivePillButton(
					text = action.label,
					icon = action.icon?.let { painterResource(it) },
					primary = index == 0,
					onClick = {
						dismiss()
						action.onClick()
					},
				)
			}
			Spacer(Modifier.height(8.dp))
			ExpressiveDialogTextButton(text = dismissLabel, onClick = dismiss)
		}
	}
}

/**
 * Expressive single-choice picker: radio [options] with an initial [selectedIndex], confirmed by a
 * pill button ([confirmLabel], error-tinted when [destructive]) or dismissed by a cancel button.
 * [onConfirm] receives the chosen index — the dialog dismisses first.
 */
fun showSingleChoiceDialog(
	context: Context,
	@DrawableRes icon: Int,
	title: String,
	options: List<String>,
	selectedIndex: Int,
	confirmLabel: String,
	destructive: Boolean = false,
	onConfirm: (Int) -> Unit,
) {
	showComposeDialog(context) { dismiss ->
		var selection by remember { mutableIntStateOf(selectedIndex) }
		ExpressiveDialogCard(
			icon = painterResource(icon),
			title = title,
		) {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				options.forEachIndexed { index, option ->
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.heightIn(min = 52.dp)
							.clip(RoundedCornerShape(16.dp))
							.clickable { selection = index }
							.padding(horizontal = 8.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						RadioButton(selected = index == selection, onClick = { selection = index })
						Spacer(Modifier.size(8.dp))
						Text(
							text = option,
							style = MaterialTheme.typography.bodyLarge,
							color = MaterialTheme.colorScheme.onSurface,
							modifier = Modifier.fillMaxWidth(),
						)
					}
				}
			}
			Spacer(Modifier.height(16.dp))
			ExpressivePillButton(
				text = confirmLabel,
				colors = if (destructive) {
					ButtonDefaults.buttonColors(
						containerColor = MaterialTheme.colorScheme.errorContainer,
						contentColor = MaterialTheme.colorScheme.onErrorContainer,
					)
				} else {
					null
				},
				onClick = {
					dismiss()
					onConfirm(selection)
				},
			)
			Spacer(Modifier.height(8.dp))
			ExpressiveDialogTextButton(
				text = context.getString(android.R.string.cancel),
				onClick = dismiss,
			)
		}
	}
}
