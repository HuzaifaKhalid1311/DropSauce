package org.koitharu.kotatsu.settings.sources.catalog.stores

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.dialog.setEditText
import org.koitharu.kotatsu.core.util.ext.copyToClipboard
import org.koitharu.kotatsu.databinding.ActivityExtensionStoresBinding
import org.koitharu.kotatsu.settings.sources.catalog.ExtensionStoreState
import javax.inject.Inject

@AndroidEntryPoint
class ExtensionStoresActivity : BaseActivity<ActivityExtensionStoresBinding>(),
	ExtensionStoresAdapter.Listener {

	private val viewModel by viewModels<ExtensionStoresViewModel>()
	private lateinit var adapter: ExtensionStoresAdapter
	private lateinit var reorderHelper: ItemTouchHelper

	@Inject
	lateinit var settings: AppSettings

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityExtensionStoresBinding.inflate(layoutInflater))
		title = getString(R.string.manage_stores)
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		adapter = ExtensionStoresAdapter(this)
		viewBinding.recyclerView.adapter = adapter
		reorderHelper = ItemTouchHelper(ReorderCallback()).also {
			it.attachToRecyclerView(viewBinding.recyclerView)
		}
		viewBinding.fabAdd.setOnClickListener { showStoreDialog(null) }
		viewBinding.switchUpdatesTab.isChecked = settings.isExtensionUpdatesTabEnabled
		viewBinding.switchUpdatesTab.setOnCheckedChangeListener { _, checked ->
			settings.isExtensionUpdatesTabEnabled = checked
		}
		lifecycleScope.launch {
			repeatOnLifecycle(Lifecycle.State.STARTED) {
				viewModel.stores.collect(adapter::submitList)
			}
		}
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

	private fun showStoreDialog(existing: ExtensionStoreState?) {
		val builder = MaterialAlertDialogBuilder(this)
			.setTitle(if (existing == null) R.string.add_store else R.string.edit_store)
		val editor = builder.setEditText(
			inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI,
			singleLine = true,
		)
		editor.hint = getString(R.string.add_repo_hint)
		editor.setText(existing?.store?.indexUrl.orEmpty())
		builder.setNegativeButton(android.R.string.cancel, null)
			.setPositiveButton(android.R.string.ok, null)
		val dialog = builder.create()
		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				val url = editor.text?.toString()?.trim().orEmpty()
				if (!url.startsWith("https://")) {
					editor.error = getString(R.string.invalid_url)
					return@setOnClickListener
				}
				setDialogBusy(dialog, true)
				lifecycleScope.launch {
					val result = if (existing == null) {
						viewModel.addStore(url)
					} else {
						viewModel.editStore(existing.store.id, url)
					}
					result.fold(
						onSuccess = { dialog.dismiss() },
						onFailure = {
							editor.error = it.message ?: getString(R.string.extensions_repo_load_error)
							setDialogBusy(dialog, false)
						},
					)
				}
			}
		}
		dialog.show()
	}

	private fun confirmRemove(item: ExtensionStoreState) {
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.remove_store)
			.setMessage(R.string.remove_store_warning)
			.setPositiveButton(R.string.remove) { _, _ -> viewModel.removeStore(item.store.id) }
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private fun setDialogBusy(dialog: AlertDialog, busy: Boolean) {
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !busy
		dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !busy
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
