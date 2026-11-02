package org.koitharu.kotatsu.settings.sources.catalog.stores

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.roundTopCorners
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.DialogCornerAction
import org.koitharu.kotatsu.core.ui.dialog.ExpressiveDialogCard
import org.koitharu.kotatsu.core.ui.dialog.showComposeDialog
import org.koitharu.kotatsu.core.ui.dialog.showConfirmDialog
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.databinding.ActivityExtensionStoresBinding
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreState

@AndroidEntryPoint
class ExtensionStoresActivity : BaseActivity<ActivityExtensionStoresBinding>(),
	ExtensionStoresAdapter.Listener {

	private val viewModel by viewModels<ExtensionStoresViewModel>()
	private lateinit var adapter: ExtensionStoresAdapter
	private lateinit var reorderHelper: ItemTouchHelper

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityExtensionStoresBinding.inflate(layoutInflater))
		title = getString(R.string.extension_stores)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		adapter = ExtensionStoresAdapter(this)
		viewBinding.recyclerView.roundTopCorners(
			radius = resources.getDimension(R.dimen.tile_corner),
			extraInset = resources.getDimensionPixelOffset(R.dimen.tile_margin_horizontal),
		)
		viewBinding.recyclerView.adapter = adapter
		reorderHelper = ItemTouchHelper(ReorderCallback()).also {
			it.attachToRecyclerView(viewBinding.recyclerView)
		}
		viewBinding.fabAdd.setOnClickListener { showStoreDialog(null) }
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.stores.collect(adapter::submitList)
			}
		}
		// After a rotation the link was already handled.
		if (savedInstanceState == null) handleAddStoreIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
		handleAddStoreIntent(intent)
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.recyclerView.updatePadding(left = bars.left, right = bars.right)
		viewBinding.content.updatePadding(bottom = bars.bottom)
		viewBinding.appbar.updatePadding(left = bars.left, right = bars.right, top = bars.top)
		viewBinding.fabAdd.updateLayoutParams<androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams> {
			bottomMargin = bars.bottom + resources.getDimensionPixelOffset(R.dimen.margin_normal)
		}
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.opt_extension_stores, menu)
		return true
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		if (item.itemId == R.id.action_refresh) {
			lifecycleScope.launch { viewModel.retry() }
			return true
		}
		return super.onOptionsItemSelected(item)
	}

	override fun onEdit(item: ExtensionStoreState) = showStoreDialog(item)

	override fun onCopy(item: ExtensionStoreState) {
		copyToClipboard(getString(R.string.store_index_url), item.store.indexUrl)
	}

	override fun onRetry() {
		lifecycleScope.launch { viewModel.retry() }
	}

	override fun onOpenLink(url: String) {
		if (!router.openExternalBrowser(url)) {
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}

	override fun onRemove(item: ExtensionStoreState) = confirmRemove(item)

	override fun onDrag(holder: RecyclerView.ViewHolder): Boolean {
		reorderHelper.startDrag(holder)
		return true
	}

	private fun showStoreDialog(existing: ExtensionStoreState?, initialUrl: String? = null) {
		showComposeDialog(this) { dismiss ->
			var url by remember { mutableStateOf(initialUrl ?: existing?.store?.indexUrl.orEmpty()) }
			var error by remember { mutableStateOf<String?>(null) }
			var isBusy by remember { mutableStateOf(false) }
			val submit: () -> Unit = {
				val value = url.trim()
				if (!value.startsWith("https://")) {
					error = getString(R.string.invalid_url)
				} else if (existing == null && viewModel.containsStoreUrl(value)) {
					error = getString(R.string.store_already_added)
				} else if (!isBusy) {
					isBusy = true
					lifecycleScope.launch {
						val result = if (existing == null) {
							viewModel.addStore(value)
						} else {
							viewModel.editStore(existing.store.id, value)
						}
						result.fold(
							onSuccess = { dismiss() },
							onFailure = {
								error = it.message ?: getString(R.string.extensions_repo_load_error)
								isBusy = false
							},
						)
					}
				}
			}
			ExpressiveDialogCard(
				icon = painterResource(R.drawable.ic_storefront),
				title = stringResource(if (existing == null) R.string.add_store else R.string.edit_store),
				message = stringResource(R.string.add_repo_message),
				cornerAction = DialogCornerAction(
					icon = painterResource(R.drawable.ic_check),
					label = stringResource(android.R.string.ok),
					enabled = !isBusy,
					onClick = submit,
				),
			) {
				OutlinedTextField(
					value = url,
					onValueChange = {
						url = it
						error = null
					},
					placeholder = {
						// Clear of the cursor, and one line so the field doesn't shrink once typing starts.
						Text(
							text = stringResource(R.string.add_repo_hint),
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
							modifier = Modifier.padding(start = 2.dp),
						)
					},
					isError = error != null,
					supportingText = error?.let { { Text(it) } },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
					keyboardActions = KeyboardActions(onDone = { submit() }),
					shape = RoundedCornerShape(16.dp),
					modifier = Modifier.fillMaxWidth(),
				)
			}
		}
	}

	private fun handleAddStoreIntent(intent: Intent?) {
		val data = intent?.data ?: return
		val isAddStore = when {
			data.scheme == "mihon" && data.host == "extension-store" -> true
			data.host == "add-repo" && data.scheme in setOf("kotatsu", "tachiyomi") -> true
			// lnreader.app's "Add to LNReader" button; the index it points at is sniffed as a plugin repo.
			data.scheme == "lnreader" && data.host == "repo" && data.path == "/add" -> true
			else -> false
		}
		if (!isAddStore) return
		val url = data.getQueryParameter("url")?.trim().orEmpty()
		if (!url.startsWith("https://")) {
			Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
			return
		}
		if (viewModel.containsStoreUrl(url)) {
			Toast.makeText(this, R.string.store_already_added, Toast.LENGTH_LONG).show()
			return
		}
		showStoreDialog(existing = null, initialUrl = url)
	}

	private fun confirmRemove(item: ExtensionStoreState) {
		showConfirmDialog(
			context = this,
			icon = R.drawable.ic_delete,
			title = getString(R.string.remove_store),
			message = getString(R.string.remove_store_warning),
			confirmLabel = getString(R.string.remove),
			destructive = true,
		) {
			viewModel.removeStore(item.store.id)
		}
	}

	private inner class ReorderCallback : ItemTouchHelper.SimpleCallback(
		ItemTouchHelper.UP or ItemTouchHelper.DOWN,
		0,
	) {
		override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

		override fun onMove(
			recyclerView: RecyclerView,
			viewHolder: RecyclerView.ViewHolder,
			target: RecyclerView.ViewHolder,
		): Boolean {
			val from = viewHolder.bindingAdapterPosition
			val to = target.bindingAdapterPosition
			return adapter.move(from, to)
		}

		override fun isLongPressDragEnabled(): Boolean = false

		override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
			super.clearView(recyclerView, viewHolder)
			val target = viewHolder.bindingAdapterPosition
			val item = adapter.itemAt(target) ?: return
			val original = viewModel.stores.value.indexOfFirst { it.store.id == item.store.id }
			if (original >= 0 && original != target) viewModel.moveStore(original, target)
		}
	}

}
