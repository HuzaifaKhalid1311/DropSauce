package org.haziffe.dropsauce.list.ui.adapter

import android.view.View
import androidx.core.view.isVisible
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.haziffe.dropsauce.R
import org.haziffe.dropsauce.core.util.ext.getDisplayMessage
import org.haziffe.dropsauce.core.util.ext.setTextAndVisible
import org.haziffe.dropsauce.databinding.ItemErrorStateBinding
import org.haziffe.dropsauce.list.ui.model.ErrorState
import org.haziffe.dropsauce.list.ui.model.ListModel

fun errorStateListAD(
	listener: ListStateHolderListener?,
) = adapterDelegateViewBinding<ErrorState, ListModel, ItemErrorStateBinding>(
	{ inflater, parent -> ItemErrorStateBinding.inflate(inflater, parent, false) },
) {

	if (listener != null) {
		val onClickListener = View.OnClickListener { v ->
			when (v.id) {
				R.id.button_retry -> listener.onRetryClick(item.exception)
				R.id.button_secondary -> listener.onSecondaryErrorActionClick(item.exception)
			}
		}

		binding.buttonRetry.setOnClickListener(onClickListener)
		binding.buttonSecondary.setOnClickListener(onClickListener)
	}

	bind {
		with(binding.textViewError) {
			text = item.exception.getDisplayMessage(context.resources)
			setCompoundDrawablesWithIntrinsicBounds(0, item.icon, 0, 0)
		}
		with(binding.buttonRetry) {
			isVisible = item.canRetry && listener != null
			setText(item.buttonText)
		}
		binding.buttonSecondary.setTextAndVisible(item.secondaryButtonText)
	}
}
