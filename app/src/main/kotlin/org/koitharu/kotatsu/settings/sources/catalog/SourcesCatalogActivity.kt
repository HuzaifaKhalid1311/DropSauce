package org.koitharu.kotatsu.settings.sources.catalog

import android.annotation.SuppressLint
import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.combine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.MangaSource
import org.koitharu.kotatsu.core.model.titleResId
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BaseActivity
import org.koitharu.kotatsu.core.ui.widgets.ChipsView
import org.koitharu.kotatsu.core.ui.widgets.ChipsView.ChipModel
import org.koitharu.kotatsu.core.util.LocaleComparator
import org.koitharu.kotatsu.core.util.ext.bindExpandedSearchTitle
import org.koitharu.kotatsu.core.util.ext.getDisplayName
import org.koitharu.kotatsu.core.util.ext.getThemeColor
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.smoothScrollToTop
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.core.ui.dialog.setEditText
import org.koitharu.kotatsu.databinding.ActivitySourcesCatalogBinding
import org.koitharu.kotatsu.extensions.install.ExtensionUpdateWorker
import org.koitharu.kotatsu.extensions.install.ShizukuExtensionInstaller
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.adapter.TypedListSpacingDecoration
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.ContentType
import java.io.File
import java.io.IOException
import javax.inject.Inject
import androidx.appcompat.R as appcompatR

@AndroidEntryPoint
class SourcesCatalogActivity : BaseActivity<ActivitySourcesCatalogBinding>(),
	ExtensionActionListener,
	AppBarOwner,
	ListHeaderClickListener,
	MenuItem.OnActionExpandListener,
	ChipsView.OnChipClickListener {

	override val appBar: AppBarLayout
		get() = viewBinding.appbar

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var shizukuInstaller: ShizukuExtensionInstaller

	@Inject
	lateinit var extensionUpdateScheduler: ExtensionUpdateWorker.Scheduler

	@Inject
	@BaseHttpClient
	lateinit var httpClient: OkHttpClient

	private val viewModel by viewModels<SourcesCatalogViewModel>()
	private val isExternalOnly by lazy(LazyThreadSafetyMode.NONE) {
		intent?.getBooleanExtra(AppRouter.KEY_SOURCE_CATALOG_EXTERNAL_ONLY, false) == true
	}
	private var isScrollToTopShown = false
	private var hasPendingUpdates = false
	private var lastSystemBarsInsets = Insets.NONE
	private val pendingInstallQueue = ArrayDeque<SourcesCatalogViewModel.InstallRequest>()
	private val pendingDownloadedInstalls = ArrayDeque<Long>()
	private val downloadRequestsById = HashMap<Long, PendingDownload>()
	private var nextDownloadId = 1L
	private var activeInstallerPackage: String? = null
	private var activeInstallerFileName: String? = null
	private var activeInstallerDownloadId = 0L
	private var isInstallerActive = false
	private var pendingUninstallPackage: String? = null
	private val appBarOffsetListener = AppBarLayout.OnOffsetChangedListener { _, _ ->
		syncFastScrollerOffset()
	}
	private val storagePermissionRequest = registerForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) { granted ->
		if (granted) {
			processInstallQueue()
		}
	}
	private val installPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		if (!canInstallPackages()) {
			Toast.makeText(this, R.string.extension_install_permission_required_message, Toast.LENGTH_LONG).show()
			closeExtensionManagerToExplore()
			return@registerForActivityResult
		}
		processInstallQueue()
		processDownloadedInstallerQueue()
	}
	private val packageInstallerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		finishActiveInstaller(refresh = true)
		processDownloadedInstallerQueue()
	}
	private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		viewModel.clearExtensionInProgress(pendingUninstallPackage)
		pendingUninstallPackage = null
		viewModel.refresh()
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		clearOldApks()
		setContentView(ActivitySourcesCatalogBinding.inflate(layoutInflater))
		setDisplayHomeAsUp(isEnabled = true, showUpAsClose = false)
		if (isExternalOnly) {
			title = getString(R.string.extension_management)
		}
		val sourcesAdapter = SourcesCatalogAdapter(extensionActionListener = this, headerClickListener = this)
		with(viewBinding.recyclerView) {
			setHasFixedSize(true)
			addItemDecoration(TypedListSpacingDecoration(context, false))
			adapter = sourcesAdapter
			fastScroller.setTrackTouchEnabled(false)
		}
		viewBinding.appbar.addOnOffsetChangedListener(appBarOffsetListener)
		viewBinding.recyclerView.doOnLayout {
			syncFastScrollerOffset()
		}
		viewBinding.chipsFilter.onChipClickListener = this
		viewModel.content.observe(this, sourcesAdapter)
		viewModel.hasUpdates.observe(this) { hasUpdates ->
			hasPendingUpdates = hasUpdates
			applyRecyclerPadding(lastSystemBarsInsets)
			if (hasUpdates) {
				if (settings.isPrivateInstallEnabled) {
					lifecycleScope.launch { extensionUpdateScheduler.startNow() }
				} else if (
					settings.isShizukuInstallerEnabled &&
					settings.isAutoUpdateExtensionsEnabled
				) {
					lifecycleScope.launch { extensionUpdateScheduler.startNow() }
				}
			}
		}
		viewModel.isRefreshing.observe(this) {
			viewBinding.swipeRefreshLayout.isRefreshing = it
		}
		viewBinding.swipeRefreshLayout.setOnRefreshListener {
			viewModel.refresh()
		}
		viewModel.onOpenPackageInstaller.observeEvent(this) { requests ->
			for (request in requests) {
				enqueueInstall(request)
			}
		}
		viewModel.onOpenUninstall.observeEvent(this) { pkg ->
			if (settings.isPrivateInstallEnabled) {
				MihonExtensionLoader.uninstallPrivateExtension(this, pkg)
				viewModel.clearExtensionInProgress(pkg)
				viewModel.refresh()
				return@observeEvent
			}
			val uri = Uri.fromParts("package", pkg, null)
			val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				Intent.ACTION_DELETE
			} else {
				@Suppress("DEPRECATION")
				Intent.ACTION_UNINSTALL_PACKAGE
			}
			val intent = Intent(action, uri)
			try {
				pendingUninstallPackage = pkg
				viewModel.setExtensionInProgress(pkg, true)
				uninstallLauncher.launch(intent)
			} catch (_: ActivityNotFoundException) {
				pendingUninstallPackage = null
				viewModel.clearExtensionInProgress(pkg)
				Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
			}
		}
		viewModel.onShowMessage.observeEvent(this) { msg ->
			Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
		}
		combine(
			viewModel.appliedFilter,
			viewModel.contentTypes,
			viewModel.locales,
			viewModel.isNsfwDisabled,
		) { filter, contentTypes, locales, isNsfwDisabled ->
			CatalogUiState(filter, contentTypes, locales, isNsfwDisabled)
		}.observe(this) {
			updateFilers(it.filter, it.contentTypes, it.locales, it.isNsfwDisabled)
		}
		addMenuProvider(SourcesCatalogMenuProvider(this, viewModel, this, isExternalOnly, settings))
		if (!settings.isShizukuInstallerEnabled && !settings.isPrivateInstallEnabled) {
			ensureInstallPermissionAccess()
		}
		handleAddRepoDeepLink(intent)
		viewBinding.buttonScrollToTop.setOnClickListener {
			viewBinding.recyclerView.smoothScrollToTop()
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
		viewBinding.appbar.removeOnOffsetChangedListener(appBarOffsetListener)
		super.onDestroy()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		lastSystemBarsInsets = bars
		applyRecyclerPadding(bars)
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
			FilterChip.NSFW_DISABLED -> viewModel.setNsfwDisabled(!chip.isChecked)
			FilterChip.LOCALE -> showLocalesMenu(chip)
			else -> Unit
		}
	}

	override fun onExtensionActionClick(item: SourceCatalogItem.Extension) {
		viewModel.onInstallEntryClick(item)
	}

	override fun onListHeaderClick(item: ListHeader, view: View) {
		if (item.payload == SourcesCatalogViewModel.HEADER_ACTION_UPDATE_ALL) {
			viewModel.updateAllExtensions()
		}
	}

	override fun onExtensionSettingsClick(item: SourceCatalogItem.Extension) {
		val sourceName = item.sourceName ?: return
		router.openSourceSettings(MangaSource(sourceName))
	}

	override fun onExtensionItemClick(item: SourceCatalogItem.Extension) {
		val sourceName = item.sourceName ?: return
		router.openList(MangaSource(sourceName), null, null)
	}

	override fun onExtensionHideClick(item: SourceCatalogItem.Extension) {
		viewModel.setExtensionHidden(item.packageName, !item.isHidden)
	}

	override fun onMenuItemActionExpand(item: MenuItem): Boolean {
		setSearchTitleExpanded(true)
		val sq = (item.actionView as? SearchView)?.query?.trim()?.toString().orEmpty()
		viewModel.performSearch(sq)
		return true
	}

	override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
		setSearchTitleExpanded(false)
		viewModel.performSearch(null)
		return true
	}

	private fun setSearchTitleExpanded(expanded: Boolean) {
		val ctl = viewBinding.collapsingToolbarLayout
		viewBinding.textTitleSearch.bindExpandedSearchTitle(ctl, ctl.title ?: title, expanded)
	}

	private fun updateFilers(
		appliedFilter: SourcesCatalogFilter,
		contentTypes: List<ContentType>,
		locales: Set<String?>,
		isNsfwDisabled: Boolean,
	) {
		val chips = ArrayList<ChipModel>(contentTypes.size + 2)
		if (locales.size > 1) {
			chips += ChipModel(
				title = appliedFilter.locale?.toLocale().getDisplayName(this),
				icon = R.drawable.ic_language,
				isDropdown = true,
				data = FilterChip.LOCALE,
			)
		}
		chips += ChipModel(
			title = getString(R.string.disable_nsfw),
			icon = R.drawable.ic_nsfw,
			isChecked = isNsfwDisabled,
			data = FilterChip.NSFW_DISABLED,
		)
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
		editor.hint = getString(R.string.add_repo_hint)
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
			dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(
				dialog.context.getThemeColor(appcompatR.attr.colorError, Color.RED),
			)
		}
		dialog.show()
	}

	fun onRemoveRepoRequested() {
		viewModel.setExternalRepoUrl(null)
	}

	fun onPrivateModeToggled() {
		val newValue = !settings.isPrivateInstallEnabled
		if (newValue) {
			lifecycleScope.launch {
				val hasPrivate = MihonExtensionLoader.hasPrivateExtensions(this@SourcesCatalogActivity)
				if (!hasPrivate) {
					val installedCount = viewModel.getMigrationExtensionCount()
					if (installedCount > 0 && !viewModel.hasExternalRepoConfigured()) {
						Toast.makeText(this@SourcesCatalogActivity, R.string.private_extensions_no_repo, Toast.LENGTH_LONG).show()
						settings.isPrivateInstallEnabled = true
						viewModel.onPrivateModeEnabled()
						invalidateOptionsMenu()
					} else if (installedCount > 0) {
						MaterialAlertDialogBuilder(this@SourcesCatalogActivity)
							.setTitle(R.string.private_extensions_migration_title)
							.setMessage(getString(R.string.private_extensions_migration_message, installedCount))
							.setPositiveButton(android.R.string.ok) { _, _ ->
								settings.isPrivateInstallEnabled = true
								viewModel.onPrivateModeEnabled()
								invalidateOptionsMenu()
							}
							.setNegativeButton(android.R.string.cancel, null)
							.show()
					} else {
						settings.isPrivateInstallEnabled = true
						viewModel.onPrivateModeEnabled()
						invalidateOptionsMenu()
					}
				} else {
					settings.isPrivateInstallEnabled = true
					viewModel.onPrivateModeEnabled()
					invalidateOptionsMenu()
				}
			}
		} else {
			settings.isPrivateInstallEnabled = false
			viewModel.onPrivateModeDisabled()
			invalidateOptionsMenu()
		}
	}

	private fun handleAddRepoDeepLink(intent: Intent?) {
		if (intent?.data?.host != "add-repo") return
		if (intent.scheme != "kotatsu" && intent.scheme != "tachiyomi") return
		val url = intent.data?.getQueryParameter("url")?.trim() ?: return
		if (url.isBlank()) return
		if (!url.startsWith("https://")) {
			Toast.makeText(this, R.string.invalid_url, Toast.LENGTH_SHORT).show()
			return
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.add_repo)
			.setMessage(getString(R.string.add_repo_confirmation, url))
			.setPositiveButton(android.R.string.ok) { _, _ ->
				viewModel.setExternalRepoUrl(url)
			}
			.setNegativeButton(android.R.string.cancel, null)
			.show()
	}

	private data class CatalogUiState(
		val filter: SourcesCatalogFilter,
		val contentTypes: List<ContentType>,
		val locales: Set<String?>,
		val isNsfwDisabled: Boolean,
	)

	private enum class FilterChip {
		LOCALE,
		NSFW_DISABLED,
	}

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

	private fun syncFastScrollerOffset() {
		val fastScroller = viewBinding.recyclerView.fastScroller
		val baseTopMargin = resources.getDimensionPixelOffset(R.dimen.fastscroll_scrollbar_margin_top)
		val appBarBottom = viewBinding.appbar.bottom
		val layoutParams = fastScroller.layoutParams
		if (layoutParams is androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) {
			val desiredTop = appBarBottom + baseTopMargin
			if (layoutParams.topMargin != desiredTop) {
				layoutParams.topMargin = desiredTop
				fastScroller.layoutParams = layoutParams
			}
		} else {
			fastScroller.translationY = appBarBottom.toFloat()
		}
	}

	private fun applyRecyclerPadding(systemBars: Insets) {
		val updatesTopPadding = if (hasPendingUpdates) {
			resources.getDimensionPixelOffset(R.dimen.margin_small)
		} else {
			0
		}
		viewBinding.recyclerView.updatePadding(
			left = systemBars.left,
			top = updatesTopPadding,
			right = systemBars.right,
			bottom = systemBars.bottom,
		)
	}

	private fun enqueueInstall(request: SourcesCatalogViewModel.InstallRequest) {
		pendingInstallQueue += request
		viewModel.setExtensionInProgress(request.packageName, true)
		processInstallQueue()
	}

	private fun processInstallQueue() {
		if (!settings.isPrivateInstallEnabled && !settings.isShizukuInstallerEnabled && !canInstallPackages()) {
			requestInstallPackagesPermission()
			return
		}
		val nextRequest = pendingInstallQueue.removeFirstOrNull() ?: return
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			storagePermissionRequest.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
			pendingInstallQueue.addFirst(nextRequest)
		} else {
			downloadAndInstallExtension(nextRequest)
		}
	}

	private fun downloadAndInstallExtension(requestModel: SourcesCatalogViewModel.InstallRequest) {
		val uri = Uri.parse(requestModel.url)
		val fileName = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "extension.apk"
		val downloadId = nextDownloadId++
		downloadRequestsById[downloadId] = PendingDownload(
			packageName = requestModel.packageName,
			fileName = fileName,
		)
		Toast.makeText(this, R.string.download_started, Toast.LENGTH_SHORT).show()
		// Download via the app's OkHttp client so proxy/DoH/SSL settings apply,
		// unlike the system DownloadManager which ignores them
		lifecycleScope.launch {
			val result = runCatching {
				withContext(Dispatchers.IO) {
					downloadApk(requestModel.url, fileName)
				}
			}
			if (result.isSuccess) {
				pendingDownloadedInstalls += downloadId
				processDownloadedInstallerQueue()
			} else {
				viewModel.clearExtensionInProgress(downloadRequestsById.remove(downloadId)?.packageName)
				Toast.makeText(this@SourcesCatalogActivity, R.string.extension_download_failed, Toast.LENGTH_LONG).show()
			}
		}
		processInstallQueue()
	}

	private fun downloadApk(url: String, fileName: String) {
		val destDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
			?: throw IOException("External storage is not available")
		val destination = File(destDir, fileName)
		val tmp = File(destDir, "$fileName.tmp")
		try {
			val request = Request.Builder().url(url).get().build()
			httpClient.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					throw IOException("HTTP ${response.code}")
				}
				response.body.byteStream().use { input ->
					tmp.outputStream().buffered().use { output ->
						input.copyTo(output)
					}
				}
			}
			if (!tmp.renameTo(destination)) {
				tmp.copyTo(destination, overwrite = true)
			}
		} finally {
			tmp.delete()
		}
	}

	private fun installDownloadedApk(downloadId: Long) {
		val pendingDownload = downloadRequestsById.remove(downloadId)
		val apkFile = getDownloadedApkFile(pendingDownload?.fileName)
		val expectedPackage = pendingDownload?.packageName ?: apkFile?.let(::getArchivePackageName)
		if (
			settings.isPrivateInstallEnabled &&
			expectedPackage != null &&
			apkFile?.isFile == true
		) {
			activeInstallerPackage = expectedPackage
			activeInstallerDownloadId = downloadId
			isInstallerActive = true
			lifecycleScope.launch {
				val success = MihonExtensionLoader.installPrivateExtensionFile(
					this@SourcesCatalogActivity, apkFile,
				)
				if (success) {
					finishActiveInstaller(refresh = true)
				} else {
					Toast.makeText(
						this@SourcesCatalogActivity,
						getString(R.string.private_extension_install_failed, expectedPackage),
						Toast.LENGTH_LONG,
					).show()
					finishActiveInstaller(refresh = false)
				}
				processDownloadedInstallerQueue()
			}
			return
		}
		if (
			settings.isShizukuInstallerEnabled &&
			expectedPackage != null &&
			apkFile?.isFile == true
		) {
			activeInstallerPackage = expectedPackage
			activeInstallerFileName = pendingDownload?.fileName
			activeInstallerDownloadId = downloadId
			isInstallerActive = true
			lifecycleScope.launch {
				when (val result = shizukuInstaller.install(apkFile, expectedPackage)) {
					ShizukuExtensionInstaller.InstallResult.Success -> {
						finishActiveInstaller(refresh = true)
						processDownloadedInstallerQueue()
					}
					ShizukuExtensionInstaller.InstallResult.Unavailable -> {
						Toast.makeText(
							this@SourcesCatalogActivity,
							R.string.shizuku_not_running,
							Toast.LENGTH_LONG,
						).show()
						finishActiveInstaller(refresh = false)
						processDownloadedInstallerQueue()
					}
					ShizukuExtensionInstaller.InstallResult.InvalidPackage -> {
						Toast.makeText(
							this@SourcesCatalogActivity,
							R.string.shizuku_invalid_package,
							Toast.LENGTH_LONG,
						).show()
						finishActiveInstaller(refresh = false)
						processDownloadedInstallerQueue()
					}
					is ShizukuExtensionInstaller.InstallResult.Failure -> {
						Toast.makeText(
							this@SourcesCatalogActivity,
							getString(R.string.shizuku_install_failed, result.message.orEmpty()),
							Toast.LENGTH_LONG,
						).show()
						finishActiveInstaller(refresh = false)
						processDownloadedInstallerQueue()
					}
				}
			}
			return
		}
		if (settings.isShizukuInstallerEnabled) {
			Toast.makeText(this, R.string.shizuku_invalid_package, Toast.LENGTH_LONG).show()
			viewModel.clearExtensionInProgress(expectedPackage ?: pendingDownload?.packageName)
			removeDownloadedApk(pendingDownload?.fileName)
			processDownloadedInstallerQueue()
			return
		}
		pendingDownload?.let { downloadRequestsById[downloadId] = it }
		installDownloadedApkWithSystem(downloadId)
	}

	private fun installDownloadedApkWithSystem(downloadId: Long) {
		if (!canInstallPackages()) {
			pendingDownloadedInstalls.addFirst(downloadId)
			requestInstallPackagesPermission()
			return
		}
		val pendingDownload = downloadRequestsById.remove(downloadId)
		activeInstallerPackage = pendingDownload?.packageName
		activeInstallerFileName = pendingDownload?.fileName
		activeInstallerDownloadId = downloadId
		isInstallerActive = true
		val apkUri = getDownloadedApkUri(pendingDownload?.fileName) ?: run {
			finishActiveInstaller(refresh = false)
			return
		}
		val installIntent = createInstallIntent(apkUri)
		try {
			grantInstallerUriPermissions(installIntent, apkUri)
			packageInstallerLauncher.launch(installIntent)
		} catch (_: ActivityNotFoundException) {
			isInstallerActive = false
			viewModel.clearExtensionInProgress(activeInstallerPackage)
			removeDownloadedApk(activeInstallerFileName)
			activeInstallerPackage = null
			activeInstallerFileName = null
			activeInstallerDownloadId = 0L
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		} catch (_: SecurityException) {
			isInstallerActive = false
			pendingDownload?.let { downloadRequestsById[downloadId] = it }
			pendingDownloadedInstalls.addFirst(downloadId)
			activeInstallerPackage = null
			activeInstallerFileName = null
			activeInstallerDownloadId = 0L
			requestInstallPackagesPermission()
			Toast.makeText(this, R.string.extension_install_permission_required_message, Toast.LENGTH_LONG).show()
		}
	}

	@Suppress("DEPRECATION")
	@SuppressLint("RequestInstallPackagesPolicy")
	private fun createInstallIntent(apkUri: Uri): Intent {
		val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
			.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
			.setDataAndType(apkUri, "application/vnd.android.package-archive")
			.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
			.putExtra(Intent.EXTRA_RETURN_RESULT, true)
		findPackageInstallerPackage(installIntent)?.let(installIntent::setPackage)
		return installIntent
	}

	private fun getDownloadedApkUri(fileName: String?): Uri? {
		getDownloadedApkFile(fileName)?.takeIf { it.isFile }?.let { file ->
			return FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.files", file)
		}
		return null
	}

	private fun getDownloadedApkFile(fileName: String?): File? {
		val downloadsDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
		if (fileName.isNullOrBlank()) {
			return null
		}
		return File(downloadsDir, fileName).takeIf { it.exists() }
	}

	private fun getArchivePackageName(apkFile: File): String? {
		return packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)?.packageName
	}

	@Suppress("DEPRECATION")
	private fun grantInstallerUriPermissions(installIntent: Intent, apkUri: Uri) {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
		for (resolveInfo in packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)) {
			val targetPackage = resolveInfo.activityInfo?.packageName ?: continue
			grantUriPermission(targetPackage, apkUri, flags)
		}
	}

	@Suppress("DEPRECATION")
	private fun findPackageInstallerPackage(installIntent: Intent): String? {
		return packageManager.queryIntentActivities(installIntent, PackageManager.MATCH_DEFAULT_ONLY)
			.firstOrNull { resolveInfo ->
				val targetPackage = resolveInfo.activityInfo?.packageName ?: return@firstOrNull false
				targetPackage.contains("packageinstaller", ignoreCase = true) ||
					targetPackage.contains("package.installer", ignoreCase = true)
			}?.activityInfo?.packageName
	}

	private fun finishActiveInstaller(
		packageName: String? = activeInstallerPackage,
		downloadId: Long = activeInstallerDownloadId,
		refresh: Boolean,
	) {
		val isCurrentInstaller = activeInstallerDownloadId == 0L ||
			downloadId == 0L ||
			activeInstallerDownloadId == downloadId
		if (isCurrentInstaller) {
			isInstallerActive = false
		}
		viewModel.clearExtensionInProgress(packageName)
		removeDownloadedApk(activeInstallerFileName)
		if (isCurrentInstaller) {
			activeInstallerPackage = null
			activeInstallerFileName = null
			activeInstallerDownloadId = 0L
		}
		if (refresh) {
			viewModel.refresh()
		}
	}

	private fun removeDownloadedApk(fileName: String?) {
		getDownloadedApkFile(fileName)?.delete()
	}

	private fun processDownloadedInstallerQueue() {
		if (isInstallerActive) {
			return
		}
		while (!isInstallerActive) {
			val downloadId = pendingDownloadedInstalls.removeFirstOrNull() ?: break
			installDownloadedApk(downloadId)
		}
	}

	private fun canInstallPackages(): Boolean {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return true
		}
		return packageManager.canRequestPackageInstalls()
	}

	private fun requestInstallPackagesPermission() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
			return
		}
		val intent = Intent(
			Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
			Uri.parse("package:$packageName"),
		)
		installPermissionLauncher.launch(intent)
	}

	private fun ensureInstallPermissionAccess() {
		if (canInstallPackages()) {
			return
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.extension_install_permission_required_title)
			.setMessage(R.string.extension_install_permission_required_message)
			.setCancelable(false)
			.setNegativeButton(android.R.string.cancel) { _, _ ->
				closeExtensionManagerToExplore()
			}
			.setPositiveButton(android.R.string.ok) { _, _ ->
				requestInstallPackagesPermission()
			}
			.show()
	}

	private fun closeExtensionManagerToExplore() {
		startActivity(
			AppRouter.homeIntent(this)
				.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
		)
		finish()
	}

	private fun clearOldApks() {
		try {
			val destDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
			destDir?.listFiles()?.forEach { file ->
				if (
					file.name.endsWith(".apk") &&
					System.currentTimeMillis() - file.lastModified() > STALE_APK_AGE_MS
				) {
					file.delete()
				}
			}
		} catch (_: Exception) {
			// Ignore
		}
	}

	private data class PendingDownload(
		val packageName: String,
		val fileName: String,
	)

	private companion object {
		const val STALE_APK_AGE_MS = 24L * 60L * 60L * 1000L
	}
}
