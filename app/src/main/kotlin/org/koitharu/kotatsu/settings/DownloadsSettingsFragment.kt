package org.koitharu.kotatsu.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.os.OpenDocumentTreeHelper
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.DownloadFormat
import org.koitharu.kotatsu.core.prefs.TriStateOption
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.core.util.ext.powerManager
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.resolveFile
import org.koitharu.kotatsu.core.util.ext.tryLaunch
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.local.data.LocalStorageManager
import org.koitharu.kotatsu.parsers.util.names
import org.koitharu.kotatsu.settings.compose.ActionSettingsItem
import org.koitharu.kotatsu.settings.compose.BaseComposeSettingsFragment
import org.koitharu.kotatsu.settings.compose.ListSettingsItem
import org.koitharu.kotatsu.settings.compose.PlainInfoSettingsItem
import org.koitharu.kotatsu.settings.compose.SettingsGroup
import org.koitharu.kotatsu.settings.compose.SettingsScaffold
import org.koitharu.kotatsu.settings.compose.SwitchSettingsItem
import org.koitharu.kotatsu.settings.compose.rememberBooleanPref
import org.koitharu.kotatsu.settings.compose.rememberStringPref
import javax.inject.Inject
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.local.data.index.LocalMangaIndex
import org.koitharu.kotatsu.core.parser.MangaRepository
import org.koitharu.kotatsu.core.parser.MangaDataRepository
import org.koitharu.kotatsu.alternatives.domain.MigrateUseCase
import org.koitharu.kotatsu.mihon.MihonExtensionManager
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.core.util.ext.toFileNameSafe
import org.koitharu.kotatsu.parsers.util.nullIfEmpty
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.koitharu.kotatsu.core.ui.dialog.buildAlertDialog
import java.io.File
import org.json.JSONObject
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@AndroidEntryPoint
class DownloadsSettingsFragment :
	BaseComposeSettingsFragment(R.string.downloads),
	SharedPreferences.OnSharedPreferenceChangeListener {

	@Inject
	lateinit var storageManager: LocalStorageManager

	@Inject
	lateinit var downloadsScheduler: DownloadWorker.Scheduler

	@Inject
	lateinit var settings: AppSettings

	@Inject
	lateinit var favouritesRepository: FavouritesRepository

	@Inject
	lateinit var localMangaIndex: LocalMangaIndex

	@Inject
	lateinit var mangaRepositoryFactory: MangaRepository.Factory

	@Inject
	lateinit var mangaDataRepository: MangaDataRepository

	@Inject
	lateinit var migrateUseCase: MigrateUseCase

	@Inject
	lateinit var mihonExtensionManager: MihonExtensionManager

	private val storageSummary = MutableStateFlow<String?>(null)
	private val directoryCount = MutableStateFlow(0)
	private val pagesDirSummary = MutableStateFlow<String?>(null)
	private val dozeAvailable = MutableStateFlow(false)

	private val pickFileTreeLauncher = OpenDocumentTreeHelper(this) {
		if (it != null) onDirectoryPicked(it)
	}

	private val startForDozeResult = registerForActivityResult(
		ActivityResultContracts.StartActivityForResult(),
	) {
		dozeAvailable.value = isDozeIgnoreAvailable()
	}

	@Composable
	override fun Content() {
		DownloadsScreen(
			storageSummary = storageSummary.asStateFlow(),
			directoryCount = directoryCount.asStateFlow(),
			pagesDirSummary = pagesDirSummary.asStateFlow(),
			dozeAvailable = dozeAvailable.asStateFlow(),
			onBack = { requireActivity().onBackPressedDispatcher.onBackPressed() },
			onPickLocalManga = { router.openDirectoriesSettings() },
			onPickLocalStorage = { router.showDirectorySelectDialog() },
			onMeteredChanged = { updateDownloadsConstraints() },
			onPickPagesDir = ::launchPagesDirPicker,
			onIgnoreDoze = ::startIgnoreDoseActivity,
			onRebuildDownloadsIndex = ::rebuildDownloadsIndex,
		)
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		settings.subscribe(this)
		refreshAsync()
		dozeAvailable.value = isDozeIgnoreAvailable()
	}

	override fun onDestroyView() {
		settings.unsubscribe(this)
		super.onDestroyView()
	}

	override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
		when (key) {
			AppSettings.KEY_LOCAL_STORAGE,
			AppSettings.KEY_LOCAL_MANGA_DIRS,
			AppSettings.KEY_PAGES_SAVE_DIR -> refreshAsync()
		}
	}

	private fun refreshAsync() {
		lifecycleScope.launch {
			val storage = withContext(Dispatchers.IO) { storageManager.getDefaultWriteableDir() }
			storageSummary.value = if (storage != null) {
				storageManager.getDirectoryDisplayName(storage, isFullPath = true)
			} else {
				getString(R.string.not_available)
			}
			directoryCount.value = withContext(Dispatchers.IO) { storageManager.getReadableDirs().size }
			val df = withContext(Dispatchers.IO) { settings.getPagesSaveDir(requireContext()) }
			pagesDirSummary.value = df?.uri?.resolveFile(requireContext())?.path
				?: df?.uri?.toString()
		}
	}

	private fun onDirectoryPicked(uri: Uri) {
		storageManager.takePermissions(uri)
		val doc = DocumentFile.fromTreeUri(requireContext(), uri)?.takeIf { it.canWrite() }
		settings.setPagesSaveDir(doc?.uri)
	}

	private fun launchPagesDirPicker() {
		val current = settings.getPagesSaveDir(requireContext())?.uri
		if (!pickFileTreeLauncher.tryLaunch(current)) {
			Snackbar.make(
				requireView(),
				R.string.operation_not_supported,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	private fun updateDownloadsConstraints() {
		lifecycleScope.launch {
			try {
				withContext(Dispatchers.Default) {
					val option = when (settings.allowDownloadOnMeteredNetwork) {
						TriStateOption.ENABLED -> true
						TriStateOption.ASK -> return@withContext
						TriStateOption.DISABLED -> false
					}
					downloadsScheduler.updateConstraints(option)
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
			}
		}
	}

	private fun isDozeIgnoreAvailable(): Boolean {
		val ctx = context ?: return false
		val pm = ctx.powerManager ?: return false
		return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
	}

	@SuppressLint("BatteryLife")
	private fun startIgnoreDoseActivity() {
		val ctx = context ?: return
		val pm = ctx.powerManager ?: return
		if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) return
		try {
			val intent = Intent(
				SystemSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
				"package:${ctx.packageName}".toUri(),
			)
			startForDozeResult.launch(intent)
		} catch (e: ActivityNotFoundException) {
			Snackbar.make(
				requireView(),
				R.string.operation_not_supported,
				Snackbar.LENGTH_SHORT,
			).show()
		}
	}

	private enum class MigrationChoice {
		MIGRATE,
		KEEP
	}

	private suspend fun showMigrationDialog(
		mangaTitle: String,
		localSource: String,
		favoriteSource: String
	): MigrationChoice = withContext(Dispatchers.Main) {
		suspendCancellableCoroutine { continuation ->
			val dialog = buildAlertDialog(requireContext()) {
				setTitle(R.string.rebuild_source_mismatch_title)
				setMessage(getString(R.string.rebuild_source_mismatch_message, mangaTitle, localSource, favoriteSource))
				setPositiveButton(getString(R.string.rebuild_migrate_to, localSource)) { _, _ ->
					if (continuation.isActive) continuation.resume(MigrationChoice.MIGRATE)
				}
				setNegativeButton(getString(R.string.rebuild_keep_on, favoriteSource)) { _, _ ->
					if (continuation.isActive) continuation.resume(MigrationChoice.KEEP)
				}
				setOnCancelListener {
					if (continuation.isActive) continuation.resume(MigrationChoice.KEEP)
				}
			}
			dialog.show()
			continuation.invokeOnCancellation {
				dialog.dismiss()
			}
		}
	}

	private fun getNormalizedSourceName(sourceName: String): String {
		val clean = sourceName.removePrefix("MIHON_").substringBefore(':')
		val sourceId = clean.toLongOrNull()
		if (sourceId != null) {
			val mihonSource = mihonExtensionManager.getMihonMangaSourceById(sourceId)
			if (mihonSource != null) {
				return mihonSource.displayName.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
			}
		}
		return clean.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
	}

	private fun isSameOrSimilarSource(sourceA: String, sourceB: String): Boolean {
		if (sourceA == sourceB) return true
		val normA = getNormalizedSourceName(sourceA)
		val normB = getNormalizedSourceName(sourceB)
		return normA.isNotEmpty() && normB.isNotEmpty() && (normA == normB || normA.contains(normB) || normB.contains(normA))
	}

	private fun extractChapterNumber(filename: String): Float? {
		val name = filename.substringBeforeLast('.')
		val regexChapter = Regex("(?i)\\b(?:c|ch|chap|chapter)\\.?\\s*([0-9]+(?:\\.[0-9]+)?)")
		regexChapter.find(name)?.groupValues?.get(1)?.toFloatOrNull()?.let {
			return it
		}
		val regexNumber = Regex("([0-9]+(?:\\.[0-9]+)?)")
		val matches = regexNumber.findAll(name).toList()
		if (matches.isNotEmpty()) {
			matches.last().groupValues[1].toFloatOrNull()?.let {
				return it
			}
		}
		return null
	}

	private fun matchChapters(
		mangaFolder: File,
		chapters: List<MangaChapter>,
		oldIndexJson: JSONObject?
	): Map<IndexedValue<MangaChapter>, String> {
		val matched = mutableMapOf<IndexedValue<MangaChapter>, String>()
		val remainingChapters = chapters.mapIndexed { idx, ch -> IndexedValue(idx, ch) }.toMutableList()
		val files = mangaFolder.listFiles()?.filter {
			val ext = it.name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
			it.isFile && (ext == "cbz" || ext == "zip")
		}.orEmpty()

		if (oldIndexJson != null) {
			val oldChaptersJson = oldIndexJson.optJSONObject("chapters")
			if (oldChaptersJson != null) {
				val keys = oldChaptersJson.keys()
				while (keys.hasNext()) {
					val key = keys.next()
					val chJson = oldChaptersJson.getJSONObject(key)
					val fileName = chJson.optString("file").nullIfEmpty() ?: continue
					val file = File(mangaFolder, fileName)
					if (!file.exists()) continue

					val number = chJson.optDouble("number", Double.NaN)
					val name = chJson.optString("name").nullIfEmpty()
					val branch = chJson.optString("branch").nullIfEmpty()

					val match = remainingChapters.find {
						(!number.isNaN() && it.value.number == number.toFloat() && (branch == null || it.value.branch == branch))
					} ?: remainingChapters.find {
						(!number.isNaN() && it.value.number == number.toFloat())
					} ?: remainingChapters.find {
						(name != null && it.value.title?.equals(name, ignoreCase = true) == true)
					}

					if (match != null) {
						matched[match] = fileName
						remainingChapters.remove(match)
					}
				}
			}
		}

		val remainingFiles = files.filter { it.name !in matched.values }
		for (file in remainingFiles) {
			val nameWithoutExt = file.name.substringBeforeLast('.')
			val indexPart = nameWithoutExt.substringBefore('_').toIntOrNull() ?: nameWithoutExt.toIntOrNull()
			if (indexPart != null) {
				val match = remainingChapters.find { it.index == indexPart }
				if (match != null) {
					matched[match] = file.name
					remainingChapters.remove(match)
					continue
				}
			}

			val numberPart = extractChapterNumber(file.name)
			if (numberPart != null) {
				val match = remainingChapters.find { it.value.number == numberPart }
				if (match != null) {
					matched[match] = file.name
					remainingChapters.remove(match)
					continue
				}
			}
		}
		return matched
	}

	private fun rebuildDownloadsIndex() {
		lifecycleScope.launch {
			withContext(Dispatchers.Main) {
				Toast.makeText(requireContext(), R.string.rebuild_downloads_index_started, Toast.LENGTH_SHORT).show()
			}
			var updatedCount = 0
			try {
				val favorites = favouritesRepository.getAllManga()
				val roots = storageManager.getReadableDirs()
				for (favorite in favorites) {
					val safeTitle = favorite.title.toFileNameSafe()
					for (root in roots) {
						val mangaFolder = File(root, safeTitle)
						if (mangaFolder.isDirectory) {
							val indexFile = File(mangaFolder, "index.json")
							val oldIndexJson = if (indexFile.isFile) {
								runCatching { JSONObject(indexFile.readText()) }.getOrNull()
							} else {
								null
							}

							var currentFavorite = favorite
							var skipFolder = false

							if (oldIndexJson != null) {
								val oldId = oldIndexJson.optLong("id", 0L)
								val oldSource = oldIndexJson.optString("source").nullIfEmpty()
								if (oldId != 0L && oldId != favorite.id && oldSource != null) {
									// Conflict detected!
									if (isSameOrSimilarSource(oldSource, favorite.source.name)) {
										// Auto resolve by overwriting index with favorite's metadata
										// Do nothing here, just keep currentFavorite
									} else {
										// Totally different source: prompt user
										val localMangaInfo = runCatching { LocalMangaParser(mangaFolder).getMangaInfo() }.getOrNull()
										if (localMangaInfo != null) {
											val choice = showMigrationDialog(
												favorite.title,
												localMangaInfo.source.name,
												favorite.source.name
											)
											if (choice == MigrationChoice.MIGRATE) {
												migrateUseCase(oldManga = favorite, newManga = localMangaInfo)
												// After migration, the favorite entry in DropSauce uses localMangaInfo's ID & source
												currentFavorite = localMangaInfo
											} else {
												skipFolder = true
											}
										} else {
											skipFolder = true
										}
									}
								}
							}

							if (skipFolder) continue

							// Fetch or retrieve chapters
							var chapters = mangaDataRepository.findMangaById(currentFavorite.id, withChapters = true)?.chapters.orEmpty()
							if (chapters.isEmpty()) {
								runCatchingCancellable {
									val repo = mangaRepositoryFactory.create(currentFavorite.source)
									val remote = repo.getDetails(currentFavorite)
									mangaDataRepository.storeManga(remote, replaceExisting = true, detailsFetched = true)
									chapters = remote.chapters.orEmpty()
								}.onFailure {
									it.printStackTraceDebug()
								}
							}

							val matched = matchChapters(mangaFolder, chapters, oldIndexJson)

							val newIndex = MangaIndex(null)
							newIndex.setMangaInfo(currentFavorite)

							val oldCoverEntry = oldIndexJson?.optString("cover_entry")?.nullIfEmpty()
							val coverName = when {
								oldCoverEntry != null && File(mangaFolder, oldCoverEntry).exists() -> oldCoverEntry
								File(mangaFolder, "cover.jpg").exists() -> "cover.jpg"
								File(mangaFolder, "cover.png").exists() -> "cover.png"
								else -> null
							}
							if (coverName != null) {
								newIndex.setCoverEntry(coverName)
							}

							for ((chapter, filename) in matched) {
								newIndex.addChapter(chapter, filename)
							}

							runCatching {
								indexFile.writeText(newIndex.toString())
								updatedCount++
							}.onFailure {
								it.printStackTraceDebug()
							}
						}
					}
				}

				localMangaIndex.update()
				withContext(Dispatchers.Main) {
					Toast.makeText(
						requireContext(),
						getString(R.string.rebuild_downloads_index_completed, updatedCount),
						Toast.LENGTH_LONG
					).show()
				}
			} catch (e: Exception) {
				e.printStackTraceDebug()
				withContext(Dispatchers.Main) {
					Toast.makeText(requireContext(), "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
				}
			}
		}
	}
}

@Composable
private fun DownloadsScreen(
	storageSummary: StateFlow<String?>,
	directoryCount: StateFlow<Int>,
	pagesDirSummary: StateFlow<String?>,
	dozeAvailable: StateFlow<Boolean>,
	onBack: () -> Unit,
	onPickLocalManga: () -> Unit,
	onPickLocalStorage: () -> Unit,
	onMeteredChanged: () -> Unit,
	onPickPagesDir: () -> Unit,
	onIgnoreDoze: () -> Unit,
	onRebuildDownloadsIndex: () -> Unit,
) {
	val ctx = LocalContext.current
	val storage by storageSummary.collectAsState()
	val dirCount by directoryCount.collectAsState()
	val pagesDir by pagesDirSummary.collectAsState()
	val showDoze by dozeAvailable.collectAsState()
	val downloadFormats = remember {
		ctx.resources.getStringArray(R.array.download_formats).toList()
	}
	val downloadFormatValues = remember { DownloadFormat.entries.names().toList() }
	val meteredOptions = remember {
		ctx.resources.getStringArray(R.array.metered_network_options).toList()
	}
	val meteredOptionValues = remember { TriStateOption.entries.names().toList() }

	var downloadFormat by rememberStringPref(
		AppSettings.KEY_DOWNLOADS_FORMAT,
		DownloadFormat.AUTOMATIC.name,
	)
	var metered by rememberStringPref(
		AppSettings.KEY_DOWNLOADS_METERED_NETWORK,
		TriStateOption.ASK.name,
	)
	var pagesDirAsk by rememberBooleanPref(AppSettings.KEY_PAGES_SAVE_ASK, true)

	LaunchedEffect(metered) { onMeteredChanged() }

	SettingsScaffold(title = stringResource(R.string.downloads), onBack = onBack) {
		item {
			SettingsGroup(title = "General") {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.local_manga_directories),
						subtitle = ctx.resources.getQuantityStringSafe(R.plurals.items, dirCount, dirCount),
						icon = R.drawable.ic_folder_file,
						
						shape = pos.shape,
						onClick = onPickLocalManga,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.manga_save_location),
						subtitle = storage,
						icon = R.drawable.ic_storage,
						
						shape = pos.shape,
						onClick = onPickLocalStorage,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.preferred_download_format),
						entries = downloadFormats,
						entryValues = downloadFormatValues,
						selectedValue = downloadFormat,
						onValueChange = { downloadFormat = it },
						icon = R.drawable.ic_file_zip,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					ListSettingsItem(
						title = stringResource(R.string.download_over_cellular),
						entries = meteredOptions,
						entryValues = meteredOptionValues,
						selectedValue = metered,
						onValueChange = { metered = it },
						icon = R.drawable.ic_network_cellular,
						
						shape = pos.shape,
					)
				}
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.rebuild_downloads_index),
						subtitle = stringResource(R.string.rebuild_downloads_index_summary),
						icon = R.drawable.ic_sync,
						shape = pos.shape,
						onClick = onRebuildDownloadsIndex,
					)
				}
				if (showDoze) {
					item { pos ->
						ActionSettingsItem(
							title = stringResource(R.string.disable_battery_optimization),
							subtitle = stringResource(R.string.disable_battery_optimization_summary_downloads),
							icon = R.drawable.ic_battery_outline,
							
							shape = pos.shape,
							onClick = onIgnoreDoze,
						)
					}
				}
			}
		}
		item {
			PlainInfoSettingsItem(
				text = stringResource(R.string.downloads_settings_info),
				icon = R.drawable.ic_info_outline,
			)
		}
		item { Spacer(Modifier.height(8.dp).fillMaxWidth()) }
		item {
			SettingsGroup(title = stringResource(R.string.pages_saving)) {
				item { pos ->
					ActionSettingsItem(
						title = stringResource(R.string.default_page_save_dir),
						subtitle = pagesDir ?: stringResource(androidx.preference.R.string.not_set),
						icon = R.drawable.ic_save,
						
						shape = pos.shape,
						onClick = onPickPagesDir,
					)
				}
				item { pos ->
					SwitchSettingsItem(
						title = stringResource(R.string.ask_for_dest_dir_every_time),
						checked = pagesDirAsk,
						onCheckedChange = { pagesDirAsk = it },
						icon = R.drawable.ic_alert_outline,
						
						shape = pos.shape,
					)
				}
			}
		}
		item { Spacer(Modifier.height(24.dp).fillMaxWidth()) }
	}
}
