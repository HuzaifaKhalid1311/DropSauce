package org.koitharu.kotatsu.core.exceptions.resolve

import android.view.View
import androidx.compose.ui.res.painterResource
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.ExpressivePillButton
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.core.util.ext.getDisplayMessage
import org.koitharu.kotatsu.parsers.exception.ParseException

class DialogErrorObserver(
	host: View,
	fragment: Fragment?,
	resolver: ExceptionResolver?,
	private val onResolved: Consumer<Boolean>?,
) : ErrorObserver(host, fragment, resolver, onResolved) {

	constructor(
		host: View,
		fragment: Fragment?,
	) : this(host, fragment, null, null)

	override suspend fun emit(value: Throwable) {
		val ctx = activity ?: host.context
		val isResolvable = canResolve(value)
		// The footer button: resolve the error, or copy a parser stack trace.
		val actionLabel = when {
			isResolvable -> ctx.getString(ExceptionResolver.getResolveStringId(value))
			value is ParseException -> ctx.getString(androidx.preference.R.string.copy)
			else -> null
		}
		showComposeDialog(ctx, onCancel = { onResolved?.accept(false) }) { dismiss ->
			ExpressiveDialogCard(
				icon = painterResource(R.drawable.ic_error_small),
				title = ctx.getString(R.string.error),
				message = value.getDisplayMessage(ctx.resources),
				cornerAction = DialogCornerAction(
					icon = painterResource(R.drawable.ic_close),
					label = ctx.getString(R.string.close),
					onClick = {
						dismiss()
						onResolved?.accept(false)
					},
				),
				footer = if (actionLabel != null) {
					{
						ExpressivePillButton(
							text = actionLabel,
							onClick = {
								dismiss()
								if (isResolvable) {
									resolve(value)
								} else {
									ctx.copyToClipboard(ctx.getString(R.string.error), value.stackTraceToString())
									// Closed without a fix, like the cross: the reader leaves instead of staying blank.
									onResolved?.accept(false)
								}
							},
						)
					}
				} else {
					null
				},
			)
		}
	}
}
