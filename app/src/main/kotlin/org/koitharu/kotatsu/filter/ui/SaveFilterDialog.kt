package org.koitharu.kotatsu.filter.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog
import org.koitharu.kotatsu.filter.data.PersistableFilter.Companion.MAX_TITLE_LENGTH

fun Fragment.showSaveFilterDialog(filter: FilterCoordinator) {
    val context = requireContext()
    showComposeDialog(context) { dismiss ->
        var name by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val submit: () -> Unit = {
            val trimmed = name.trim()
            when {
                trimmed.isEmpty() -> error = context.getString(R.string.invalid_value_message)
                filter.isSavedFilterNameTaken(trimmed) -> error = context.getString(R.string.filter_name_exists)
                else -> {
                    dismiss()
                    filter.saveCurrentFilter(trimmed)
                }
            }
        }
        ExpressiveDialogCard(
            icon = painterResource(R.drawable.ic_save),
            title = stringResource(R.string.save_filter),
            cornerAction = DialogCornerAction(
                icon = painterResource(R.drawable.ic_check),
                label = stringResource(R.string.save),
                onClick = submit,
            ),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(MAX_TITLE_LENGTH)
                    error = null
                },
                placeholder = { Text(stringResource(R.string.enter_name)) },
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
