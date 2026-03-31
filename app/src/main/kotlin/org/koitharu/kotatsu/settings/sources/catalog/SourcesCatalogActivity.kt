package org.koitharu.kotatsu.settings.sources.catalog

import android.os.Bundle
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.ui.util.FadingAppbarMediator
import org.koitharu.kotatsu.core.ui.util.ReversibleActionObserver
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.ui.widgets.ChipsView.ChipModel
import org.koitharu.kotatsu.core.util.LocaleComparator
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.core.ui.dialog.setEditText
import org.koitharu.kotatsu.databinding.ActivitySourcesCatalogBinding
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.ContentType

@AndroidEntryPoint
class SourcesCatalogActivity : BaseActivity<ActivitySourcesCatalogBinding>(),
	OnListItemClickListener<SourceCatalogItem.Source>,
	ExtensionActionListener,
	AppBarOwner,
	MenuItem.OnActionExpandListener,
	ChipsView.OnChipClickListener {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	private val viewModel by viewModels<SourcesCatalogViewModel>()
	private val isExternalOnly by lazy(LazyThreadSafetyMode.NONE) {
		intent?.getBooleanExtra(AppRouter.KEY_SOURCE_CATALOG_EXTERNAL_ONLY, false) == true
	}
	private var isScrollToTopShown = false
	private val downloadManager by lazy(LazyThreadSafetyMode.NONE) {
		getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
	}
	private val pendingInstallerDownloads = HashSet<Long>()
	private var pendingInstallUrl: String? = null
	private val extensionDownloadReceiver = ExtensionDownloadReceiver { downloadId ->
		if (pendingInstallerDownloads.remove(downloadId)) {
			installDownloadedApk(downloadId)
		}
	}
	private val storagePermissionRequest = registerForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { granted ->
		if (granted) {
			pendingInstallUrl?.let(::downloadAndInstallExtension)
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		clearOldApks()
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		if (isExternalOnly) {
			title = getString(R.string.extension_management)
		}
		val sourcesAdapter = SourcesCatalogAdapter(this, this)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			adapter = sourcesAdapter
		}
		viewBinding.chipsFilter.onChipClickListener = this
		if (isExternalOnly) {
			viewModel.setMode(SourcesCatalogMode.MIHON)
		} else {
			intent?.getStringExtra(AppRouter.KEY_SOURCE_CATALOG_MODE)?.let { modeName ->
				SourcesCatalogMode.entries.firstOrNull { it.name == modeName }?.let { mode ->
					viewModel.setMode(mode)
				}
			}
		}
		FadingAppbarMediator(viewBinding.appbar, viewBinding.toolbar).bind()
		viewModel.content.observe(this, sourcesAdapter)
		viewModel.isRefreshing.observe(this) {
			viewBinding.swipeRefreshLayout.isRefreshing = it
		}
		viewBinding.swipeRefreshLayout.setOnRefreshListener {
			viewModel.refresh()
		}
		viewModel.onActionDone.observeEvent(
			this,
			ReversibleActionObserver(viewBinding.recyclerView),
		)
		viewModel.onOpenPackageInstaller.observeEvent(this) { url ->
			onInstallExtensionRequested(url)
		}
		viewModel.onOpenUninstall.observeEvent(this) { pkg ->
			val uri = Uri.fromParts("package", pkg, null)
			val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				Intent.ACTION_DELETE
			} else {
				@Suppress("DEPRECATION")
				Intent.ACTION_UNINSTALL_PACKAGE
			}
			startActivity(Intent(action, uri))
		}
		viewModel.onShowMessage.observeEvent(this) { msg ->
			Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
		}
		combine(
			viewModel.appliedFilter,
			viewModel.hasNewSources,
			viewModel.contentTypes,
			viewModel.locales,
		) { filter, hasNewSources, contentTypes, locales ->
			CatalogUiState(filter, hasNewSources, contentTypes, locales)
		}.observe(this) {
			updateFilers(it.filter, it.hasNewSources, it.contentTypes, it.locales)
		}
		addMenuProvider(SourcesCatalogMenuProvider(this, viewModel, this, isExternalOnly))
		ContextCompat.registerReceiver(
			this,
			extensionDownloadReceiver,
			android.content.IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
			ContextCompat.RECEIVER_EXPORTED,
		)
		viewBinding.buttonScrollToTop.setOnClickListener {
			viewBinding.recyclerView.smoothScrollToPosition(0)
		}
		viewBinding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
			override fun onScrolled(recyclerView: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
				super.onScrolled(recyclerView, dx, dy)
				updateScrollToTopVisibility()
			}
		})
		updateScrollToTopVisibility()
	}

	override fun onDestroy() {
		unregisterReceiver(extensionDownloadReceiver)
		clearOldApks()
		super.onDestroy()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		viewBinding.recyclerView.updatePadding(
			left = bars.left,
			top = 0,
			right = bars.right,
			bottom = bars.bottom,
		)
		viewBinding.appbar.updatePadding(
			left = bars.left,
			right = bars.right,
			top = bars.top,
		)
		viewBinding.buttonScrollToTop.updateLayoutParams<androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams> {
			leftMargin = bars.left
			rightMargin = bars.right
			bottomMargin = bars.bottom + resources.getDimensionPixelOffset(R.dimen.margin_normal)
		}
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onChipClick(chip: Chip, data: Any?) {
		when (data) {
			is ContentType -> viewModel.setContentType(data, !chip.isChecked)
			is Boolean -> viewModel.setNewOnly(!chip.isChecked)
			else -> showLocalesMenu(chip)
		}
	}

	override fun onItemClick(item: SourceCatalogItem.Source, view: View) {
		router.openList(item.source, null, null)
	}

	override fun onItemLongClick(item: SourceCatalogItem.Source, view: View): Boolean {
		viewModel.addSource(item.source)
		return false
	}

	override fun onExtensionActionClick(item: SourceCatalogItem.Extension) {
		viewModel.onInstallEntryClick(item)
	}

	override fun onExtensionSettingsClick(item: SourceCatalogItem.Extension) {
		val sourceName = item.sourceName ?: return
		router.openSourceSettings(MangaSource(sourceName))
	}

	override fun onExtensionItemClick(item: SourceCatalogItem.Extension) {
		val sourceName = item.sourceName ?: return
		router.openList(MangaSource(sourceName), null, null)
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		val sq = (item.actionView as? SearchView)?.query?.trim()?.toString().orEmpty()
		viewModel.performSearch(sq)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		viewModel.performSearch(null)
		return true
	}

	private fun updateFilers(
		appliedFilter: SourcesCatalogFilter,
		hasNewSources: Boolean,
		contentTypes: List<ContentType>,
		locales: Set<String?>,
	) {
		val chips = ArrayList<ChipModel>(contentTypes.size + 2)
		if (locales.size > 1 || appliedFilter.mode == SourcesCatalogMode.MIHON) {
			chips += ChipModel(
				title = appliedFilter.locale?.toLocale().getDisplayName(this),
				icon = R.drawable.ic_language,
				isDropdown = true,
			)
		}
		if (hasNewSources && appliedFilter.mode == SourcesCatalogMode.BUILTIN) {
			chips += ChipModel(
				title = getString(R.string._new),
				icon = R.drawable.ic_updated,
				isChecked = appliedFilter.isNewOnly,
				data = true,
			)
		}
		contentTypes.mapTo(chips) { type ->
			ChipModel(
				title = getString(type.titleResId),
				isChecked = type in appliedFilter.types,
				data = type,
			)
		}
		viewBinding.chipsFilter.setChips(chips)
	}

	private fun showLocalesMenu(anchor: View) {
		val locales = viewModel.locales.value.mapTo(ArrayList(viewModel.locales.value.size)) {
			it to it?.toLocale()
		}
		locales.sortWith(compareBy(nullsFirst(LocaleComparator())) { it.second })
		val menu = PopupMenu(this, anchor)
		for ((i, lc) in locales.withIndex()) {
			menu.menu.add(Menu.NONE, Menu.NONE, i, lc.second.getDisplayName(this))
		}
		menu.setOnMenuItemClickListener {
			viewModel.setLocale(locales.getOrNull(it.order)?.first)
			true
		}
		menu.show()
	}

	fun onManageRepoRequested() {
		val hasRepo = viewModel.hasExternalRepoConfigured()
		val dialogBuilder = MaterialAlertDialogBuilder(this)
			.setTitle(if (hasRepo) R.string.change_repo else R.string.add_repo)
		val editor = dialogBuilder.setEditText(
			inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_URI,
			singleLine = true,
		)
		editor.setText(viewModel.getExternalRepoUrl().orEmpty())
		editor.hint = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json"
		if (hasRepo) {
			dialogBuilder.setNeutralButton(R.string.remove_repo) { _, _ ->
				onRemoveRepoRequested()
			}
			dialogBuilder.setNegativeButton(android.R.string.cancel, null)
		} else {
			dialogBuilder.setNegativeButton(android.R.string.cancel, null)
		}
		dialogBuilder.setPositiveButton(android.R.string.ok, null)
		val dialog = dialogBuilder.create()
		dialog.setOnShowListener {
			dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
				val value = editor.text?.toString()?.trim().orEmpty()
				if (!value.startsWith("https://")) {
					editor.error = getString(R.string.invalid_url)
					return@setOnClickListener
				}
				viewModel.setExternalRepoUrl(value)
				dialog.dismiss()
			}
		}
		dialog.show()
	}

	fun onRemoveRepoRequested() {
		viewModel.setExternalRepoUrl(null)
	}

	private data class CatalogUiState(
		val filter: SourcesCatalogFilter,
		val hasNewSources: Boolean,
		val contentTypes: List<ContentType>,
		val locales: Set<String?>,
	)

	private fun updateScrollToTopVisibility() {
		val layoutManager = viewBinding.recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
		val shouldShow = layoutManager.findFirstVisibleItemPosition() >= 6
		if (shouldShow == isScrollToTopShown) {
			return
		}
		isScrollToTopShown = shouldShow
		viewBinding.buttonScrollToTop.animate().cancel()
		if (shouldShow) {
			viewBinding.buttonScrollToTop.alpha = 0f
			viewBinding.buttonScrollToTop.visibility = View.VISIBLE
			viewBinding.buttonScrollToTop.animate()
				.alpha(1f)
				.setDuration(160L)
				.start()
		} else {
			viewBinding.buttonScrollToTop.animate()
				.alpha(0f)
				.setDuration(160L)
				.withEndAction {
					viewBinding.buttonScrollToTop.visibility = View.GONE
				}
				.start()
		}
	}

	private fun onInstallExtensionRequested(url: String) {
		pendingInstallUrl = url
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			storagePermissionRequest.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
		} else {
			downloadAndInstallExtension(url)
		}
	}

	private fun downloadAndInstallExtension(url: String) {
		val uri = Uri.parse(url)
		val fileName = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "extension.apk"
		val request = DownloadManager.Request(uri)
			.setTitle(fileName)
			.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName)
			.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
			.setMimeType("application/vnd.android.package-archive")
		val downloadId = downloadManager.enqueue(request)
		pendingInstallerDownloads += downloadId
		Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
	}

	private fun installDownloadedApk(downloadId: Long) {
		val apkUri = downloadManager.getUriForDownloadedFile(downloadId) ?: return
		val mime = downloadManager.getMimeTypeForDownloadedFile(downloadId)
			?: "application/vnd.android.package-archive"
		val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
			.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
			.setDataAndType(apkUri, mime)
			.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
		try {
			startActivity(installIntent)
		} catch (_: ActivityNotFoundException) {
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
	}

	private fun clearOldApks() {
		try {
			val destDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
			destDir?.listFiles()?.forEach { file ->
				if (file.name.endsWith(".apk")) {
					file.delete()
				}
			}
		} catch (e: Exception) {
			// Ignore
		}
	}

	private class ExtensionDownloadReceiver(
		private val onComplete: (Long) -> Unit,
	) : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
				val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0L)
				if (downloadId != 0L) {
					onComplete(downloadId)
				}
			}
		}
	}
}
