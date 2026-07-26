package org.koitharu.kotatsu.settings.sources.catalog

import android.annotation.SuppressLint
import android.os.Bundle
import android.content.ActivityNotFoundException
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.core.graphics.Insets
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.chip.Chip
import com.google.android.material.tabs.TabLayoutMediator
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
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import org.koitharu.kotatsu.core.util.ext.smoothScrollToTop
import org.koitharu.kotatsu.core.util.ext.recyclerView
import org.koitharu.kotatsu.core.util.ext.toLocale
import org.koitharu.kotatsu.databinding.ActivitySourcesCatalogBinding
import org.koitharu.kotatsu.extensions.install.ExtensionUpdateWorker
import org.koitharu.kotatsu.extensions.install.ShizukuExtensionInstaller
import org.koitharu.kotatsu.mihon.MihonExtensionLoader
import org.koitharu.kotatsu.list.ui.adapter.ListHeaderClickListener
import org.koitharu.kotatsu.list.ui.model.ListHeader
import org.koitharu.kotatsu.main.ui.owners.AppBarOwner
import org.koitharu.kotatsu.parsers.model.ContentType
import java.io.File
import java.io.IOException
import javax.inject.Inject

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
	lateinit var storeManager: ExtensionStoreManager

	@Inject
	@BaseHttpClient
	lateinit var httpClient: OkHttpClient

	private val viewModel by viewModels<SourcesCatalogViewModel>()
	private val isExternalOnly by lazy(LazyThreadSafetyMode.NONE) {
		intent?.getBooleanExtra(AppRouter.KEY_SOURCE_CATALOG_EXTERNAL_ONLY, false) == true
	}
	private val isAutoMigrate by lazy(LazyThreadSafetyMode.NONE) {
		intent?.getBooleanExtra(AppRouter.KEY_SOURCE_CATALOG_AUTO_MIGRATE, false) == true
	}
	private var isScrollToTopShown = false
	private val pendingInstallQueue = ArrayDeque<SourcesCatalogViewModel.InstallRequest>()
	private val pendingDownloadedInstalls = ArrayDeque<Long>()
	private val downloadRequestsById = HashMap<Long, PendingDownload>()
	private var nextDownloadId = 1L
	private var activeInstallerPackage: String? = null
	private var activeInstallerFileName: String? = null
	private var activeInstallerDownloadId = 0L
	private var activeInstallerStoreId: String? = null
	private var activeInstallerMode: ExtensionInstallMode? = null
	private var isInstallerActive = false
	private var pendingUninstallPackage: String? = null
	private var pendingReplacementDownloadId: Long? = null
	private lateinit var pagesAdapter: SourcesCatalogPagesAdapter
	private var selectedPageId = ExtensionCatalogPage.Available.id
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
	private val packageInstallerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
		finishActiveInstaller(refresh = true, installSucceeded = result.resultCode == RESULT_OK)
		processDownloadedInstallerQueue()
	}
	private val uninstallLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
		val replacementDownloadId = pendingReplacementDownloadId
		pendingReplacementDownloadId = null
		if (replacementDownloadId != null) {
			val pending = downloadRequestsById[replacementDownloadId]
			val isRemoved = pending?.packageName?.let { packageName ->
				runCatching { packageManager.getPackageInfo(packageName, 0) }.isFailure
			} == true
			if (isRemoved) {
				downloadRequestsById[replacementDownloadId] = pending.copy(systemRemovalCompleted = true)
				pendingDownloadedInstalls.addFirst(replacementDownloadId)
			} else {
				viewModel.clearExtensionInProgress(pending?.packageName)
				removeDownloadedApk(pending?.fileName)
				downloadRequestsById.remove(replacementDownloadId)
			}
			processDownloadedInstallerQueue()
			return@registerForActivityResult
		}
		viewModel.clearExtensionInProgress(pendingUninstallPackage)
		pendingUninstallPackage?.takeIf { packageName ->
			runCatching { packageManager.getPackageInfo(packageName, 0) }.isFailure
		}?.let { packageName ->
			storeManager.removeOwner(ExtensionInstallMode.SYSTEM, packageName)
		}
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
		pagesAdapter = SourcesCatalogPagesAdapter(
			extensionActionListener = this,
			headerClickListener = this,
			listener = object : SourcesCatalogPagesAdapter.Listener {
				override fun onRefresh() = viewModel.refresh()
				override fun onPageScrolled() = updateScrollToTopVisibility()
				override fun onUpdateAll() = viewModel.updateAllExtensions()
			},
		)
		viewBinding.pager.adapter = pagesAdapter
		viewBinding.pager.offscreenPageLimit = 1
		viewBinding.pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
			override fun onPageSelected(position: Int) {
				val page = pagesAdapter.pageAt(position) ?: return
				selectedPageId = page.id
				viewModel.selectPage(page.id)
				invalidateOptionsMenu()
				updateScrollToTopVisibility()
			}
		})
		TabLayoutMediator(viewBinding.tabs, viewBinding.pager) { tab, position ->
			tab.text = when (val page = pagesAdapter.pageAt(position)) {
				ExtensionCatalogPage.Available -> getString(R.string.available)
				ExtensionCatalogPage.Empty -> ""
				is ExtensionCatalogPage.Store -> page.title
				null -> ""
			}
		}.attach()
		viewBinding.chipsFilter.onChipClickListener = this
		viewModel.pages.observe(this) { pages ->
			pagesAdapter.submitPages(pages)
			viewBinding.tabs.isVisible = pages.none { it == ExtensionCatalogPage.Empty }
			viewBinding.scrollViewChips.isVisible = shouldShowCatalogFilters(pages)
			val selected = pages.indexOfFirst { it.id == selectedPageId }.let { if (it >= 0) it else 0 }
			pages.getOrNull(selected)?.let { page ->
				selectedPageId = page.id
				viewModel.selectPage(page.id)
			}
			if (viewBinding.pager.currentItem != selected) {
				viewBinding.pager.setCurrentItem(selected, false)
			}
			invalidateOptionsMenu()
		}
		viewModel.content.observe(this) { page ->
			pagesAdapter.submitContent(page.pageId, page.items)
		}
		viewModel.hasUpdates.observe(this) { hasUpdates ->
			invalidateOptionsMenu()
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
			pagesAdapter.setRefreshing(it)
		}
		viewModel.onOpenPackageInstaller.observeEvent(this) { requests ->
			for (request in requests) {
				confirmAndEnqueueInstall(request)
			}
		}
		viewModel.onOpenUninstall.observeEvent(this) { pkg ->
			if (settings.isPrivateInstallEnabled) {
				MihonExtensionLoader.uninstallPrivateExtension(this, pkg)
				storeManager.removeOwner(ExtensionInstallMode.SANDBOX, pkg)
				viewModel.clearExtensionInProgress(pkg)
				viewModel.onPrivateExtensionChanged()
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
		addMenuProvider(SourcesCatalogMenuProvider(this, viewModel, this))
		if (!settings.isShizukuInstallerEnabled && !settings.isPrivateInstallEnabled) {
			ensureInstallPermissionAccess()
		}
		if (isAutoMigrate && settings.isPrivateInstallEnabled) {
			lifecycleScope.launch {
				viewModel.isRefreshing.value = true
				val count = withContext(Dispatchers.IO) {
					viewModel.reloadAndCheckMigration()
				}
				viewModel.isRefreshing.value = false
				if (count <= 0) return@launch
				if (!viewModel.hasExternalRepoConfigured()) {
					Toast.makeText(
						this@SourcesCatalogActivity,
						R.string.private_extensions_no_repo,
						Toast.LENGTH_LONG,
					).show()
					return@launch
				}
				MaterialAlertDialogBuilder(this@SourcesCatalogActivity)
					.setTitle(R.string.private_extensions_migration_title)
					.setMessage(getString(R.string.private_extensions_migration_message, count))
					.setPositiveButton(android.R.string.ok) { _, _ ->
						viewModel.performMigration()
					}
					.setNegativeButton(android.R.string.cancel, null)
					.show()
			}
		}
		viewBinding.buttonScrollToTop.setOnClickListener {
			currentRecyclerView()?.smoothScrollToTop()
		}
		updateScrollToTopVisibility()
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
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
		// Section headers on store tabs are labels only.
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
		router.openExtensionStores()
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
		val layoutManager = currentRecyclerView()?.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
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

	private fun applyRecyclerPadding(systemBars: Insets) {
		if (::pagesAdapter.isInitialized) pagesAdapter.setInsets(systemBars)
	}

	private fun currentRecyclerView() =
		(viewBinding.pager.recyclerView
			?.findViewHolderForAdapterPosition(viewBinding.pager.currentItem) as? SourcesCatalogPagesAdapter.Holder)
			?.binding
			?.recyclerView

	private fun enqueueInstall(request: SourcesCatalogViewModel.InstallRequest) {
		val alreadyQueued = pendingInstallQueue.any { it.packageName == request.packageName && it.mode == request.mode } ||
			downloadRequestsById.values.any { it.packageName == request.packageName && it.mode == request.mode } ||
			(activeInstallerPackage == request.packageName && activeInstallerMode == request.mode)
		if (alreadyQueued) return
		pendingInstallQueue += request
		viewModel.setExtensionInProgress(request.packageName, true)
		processInstallQueue()
	}

	private fun confirmAndEnqueueInstall(request: SourcesCatalogViewModel.InstallRequest) {
		val replacement = request.replacement
		if (replacement == null) {
			enqueueInstall(request)
			return
		}
		MaterialAlertDialogBuilder(this)
			.setTitle(R.string.replace_extension_store_title)
			.setMessage(
				getString(
					R.string.replace_extension_store_message,
					request.packageName,
					replacement.currentStoreName,
					replacement.newStoreName,
				),
			)
			.setNegativeButton(R.string.keep_current_store) { _, _ ->
				viewModel.clearExtensionInProgress(request.packageName)
			}
			.setPositiveButton(R.string.replace_store) { _, _ -> enqueueInstall(request) }
			.setOnCancelListener { viewModel.clearExtensionInProgress(request.packageName) }
			.show()
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
		val downloadId = nextDownloadId++
		val sourceFileName = uri.lastPathSegment?.takeIf { it.isNotBlank() } ?: "extension.apk"
		val fileName = "$downloadId-$sourceFileName"
		downloadRequestsById[downloadId] = PendingDownload(
			packageName = requestModel.packageName,
			fileName = fileName,
			storeId = requestModel.storeId,
			mode = requestModel.mode,
			isProviderReplacement = requestModel.replacement != null,
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
		val archive = apkFile?.let(::getArchivePackageInfo)
		if (
			pendingDownload == null ||
			apkFile == null ||
			!apkFile.isFile ||
			archive == null ||
			archive.packageName != pendingDownload.packageName ||
			!MihonExtensionLoader.isPackageAnExtensionStatic(archive) ||
			MihonExtensionLoader.getSignatures(archive).isEmpty()
		) {
			viewModel.clearExtensionInProgress(pendingDownload?.packageName)
			removeDownloadedApk(pendingDownload?.fileName)
			Toast.makeText(this, R.string.shizuku_invalid_package, Toast.LENGTH_LONG).show()
			processDownloadedInstallerQueue()
			return
		}
		val expectedPackage = pendingDownload.packageName
		if (
			pendingDownload.mode == ExtensionInstallMode.SANDBOX &&
			apkFile.isFile
		) {
			activeInstallerPackage = expectedPackage
			activeInstallerStoreId = pendingDownload.storeId
			activeInstallerMode = pendingDownload.mode
			activeInstallerDownloadId = downloadId
			isInstallerActive = true
			lifecycleScope.launch {
				val success = MihonExtensionLoader.installPrivateExtensionFile(
					this@SourcesCatalogActivity,
					apkFile,
					replaceExistingProvider = pendingDownload.isProviderReplacement,
					expectedPackageName = expectedPackage,
				)
				if (success) {
					finishActiveInstaller(refresh = false, installSucceeded = true)
					viewModel.onPrivateExtensionChanged()
				} else {
					Toast.makeText(
						this@SourcesCatalogActivity,
						getString(R.string.private_extension_install_failed, expectedPackage),
						Toast.LENGTH_LONG,
					).show()
					finishActiveInstaller(refresh = false, installSucceeded = false)
				}
				processDownloadedInstallerQueue()
			}
			return
		}
		if (
			pendingDownload.mode == ExtensionInstallMode.SYSTEM &&
			pendingDownload.isProviderReplacement &&
			!pendingDownload.systemRemovalCompleted &&
			apkFile.isFile &&
			needsSystemRemovalForReplacement(expectedPackage, apkFile)
		) {
			downloadRequestsById[downloadId] = pendingDownload
			pendingReplacementDownloadId = downloadId
			launchSystemUninstall(expectedPackage)
			return
		}
		if (
			settings.isShizukuInstallerEnabled &&
			apkFile.isFile
		) {
			activeInstallerPackage = expectedPackage
			activeInstallerStoreId = pendingDownload.storeId
			activeInstallerMode = pendingDownload.mode
			activeInstallerFileName = pendingDownload.fileName
			activeInstallerDownloadId = downloadId
			isInstallerActive = true
			lifecycleScope.launch {
				when (val result = shizukuInstaller.install(apkFile, expectedPackage)) {
					ShizukuExtensionInstaller.InstallResult.Success -> {
						finishActiveInstaller(refresh = true, installSucceeded = true)
						processDownloadedInstallerQueue()
					}
					ShizukuExtensionInstaller.InstallResult.Unavailable -> {
						Toast.makeText(
							this@SourcesCatalogActivity,
							R.string.shizuku_not_running,
							Toast.LENGTH_LONG,
						).show()
						finishActiveInstaller(refresh = false, installSucceeded = false)
						processDownloadedInstallerQueue()
					}
					ShizukuExtensionInstaller.InstallResult.InvalidPackage -> {
						Toast.makeText(
							this@SourcesCatalogActivity,
							R.string.shizuku_invalid_package,
							Toast.LENGTH_LONG,
						).show()
						finishActiveInstaller(refresh = false, installSucceeded = false)
						processDownloadedInstallerQueue()
					}
					is ShizukuExtensionInstaller.InstallResult.Failure -> {
						Toast.makeText(
							this@SourcesCatalogActivity,
							getString(R.string.shizuku_install_failed, result.message.orEmpty()),
							Toast.LENGTH_LONG,
						).show()
						finishActiveInstaller(refresh = false, installSucceeded = false)
						processDownloadedInstallerQueue()
					}
				}
			}
			return
		}
		if (settings.isShizukuInstallerEnabled) {
			Toast.makeText(this, R.string.shizuku_invalid_package, Toast.LENGTH_LONG).show()
			viewModel.clearExtensionInProgress(expectedPackage)
			removeDownloadedApk(pendingDownload.fileName)
			processDownloadedInstallerQueue()
			return
		}
		downloadRequestsById[downloadId] = pendingDownload
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
		activeInstallerStoreId = pendingDownload?.storeId
		activeInstallerMode = pendingDownload?.mode
		activeInstallerFileName = pendingDownload?.fileName
		activeInstallerDownloadId = downloadId
		isInstallerActive = true
		val apkUri = getDownloadedApkUri(pendingDownload?.fileName) ?: run {
			finishActiveInstaller(refresh = false, installSucceeded = false)
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

	@Suppress("DEPRECATION")
	private fun getArchivePackageInfo(apkFile: File): PackageInfo? {
		val flags = PackageManager.GET_META_DATA or
			PackageManager.GET_CONFIGURATIONS or
			PackageManager.GET_SIGNATURES or
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
		return packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
	}

	@Suppress("DEPRECATION")
	private fun needsSystemRemovalForReplacement(packageName: String, apkFile: File): Boolean {
		val flags = PackageManager.GET_SIGNATURES or
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
		val installed = runCatching { packageManager.getPackageInfo(packageName, flags) }.getOrNull()
			?: return false
		val archive = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags) ?: return true
		val signerChanged = MihonExtensionLoader.getSignatures(installed).toSet() !=
			MihonExtensionLoader.getSignatures(archive).toSet()
		val isDowngrade = PackageInfoCompat.getLongVersionCode(archive) <
			PackageInfoCompat.getLongVersionCode(installed)
		return signerChanged || isDowngrade
	}

	private fun launchSystemUninstall(packageName: String) {
		val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			Intent.ACTION_DELETE
		} else {
			@Suppress("DEPRECATION")
			Intent.ACTION_UNINSTALL_PACKAGE
		}
		try {
			uninstallLauncher.launch(Intent(action, Uri.fromParts("package", packageName, null)))
		} catch (_: ActivityNotFoundException) {
			val downloadId = pendingReplacementDownloadId
			pendingReplacementDownloadId = null
			val pending = downloadId?.let(downloadRequestsById::remove)
			viewModel.clearExtensionInProgress(pending?.packageName)
			removeDownloadedApk(pending?.fileName)
			Toast.makeText(this, R.string.operation_not_supported, Toast.LENGTH_SHORT).show()
		}
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
		installSucceeded: Boolean = false,
	) {
		val isCurrentInstaller = activeInstallerDownloadId == 0L ||
			downloadId == 0L ||
			activeInstallerDownloadId == downloadId
		if (isCurrentInstaller) {
			isInstallerActive = false
		}
		viewModel.clearExtensionInProgress(packageName)
		if (installSucceeded && packageName != null && activeInstallerStoreId != null && activeInstallerMode != null) {
			storeManager.setOwner(activeInstallerMode!!, packageName, activeInstallerStoreId!!)
		}
		removeDownloadedApk(activeInstallerFileName)
		if (isCurrentInstaller) {
			activeInstallerPackage = null
			activeInstallerFileName = null
			activeInstallerDownloadId = 0L
			activeInstallerStoreId = null
			activeInstallerMode = null
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
		val storeId: String,
		val mode: ExtensionInstallMode,
		val isProviderReplacement: Boolean,
		val systemRemovalCompleted: Boolean = false,
	)

	private companion object {
		const val STALE_APK_AGE_MS = 24L * 60L * 60L * 1000L
	}
}
