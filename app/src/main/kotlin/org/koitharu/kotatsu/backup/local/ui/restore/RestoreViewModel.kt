package org.koitharu.kotatsu.backup.local.ui.restore

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.backup.local.data.model.BackupIndex
import org.koitharu.kotatsu.backup.local.domain.BackupSection
import org.koitharu.kotatsu.core.nav.AppRouter
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.core.util.ext.toUriOrNull
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Date
import java.util.EnumMap
import java.util.EnumSet
import java.util.zip.ZipInputStream
import javax.inject.Inject

@HiltViewModel
class RestoreViewModel @Inject constructor(
	savedStateHandle: SavedStateHandle,
	@ApplicationContext context: Context,
) : BaseViewModel() {

	val uri = savedStateHandle.get<String>(AppRouter.KEY_FILE)?.toUriOrNull()
	private val contentResolver = context.contentResolver

	val availableEntries = MutableStateFlow<List<BackupSectionModel>>(emptyList())
	val backupDate = MutableStateFlow<Date?>(null)

	init {
		launchLoadingJob(Dispatchers.Default) {
			loadBackupInfo()
		}
	}

	private suspend fun loadBackupInfo() {
		val sections = runInterruptible(Dispatchers.IO) {
			val source = uri ?: throw FileNotFoundException()
			ZipInputStream(contentResolver.openInputStream(source)).use { stream ->
				val result = EnumSet.noneOf(BackupSection::class.java)
				var entry = stream.nextEntry
				while (entry != null) {
					val section = BackupSection.of(entry)
					if (section != null) {
						result.add(section)
						if (section == BackupSection.INDEX) {
							backupDate.value = stream.readIndexDate()
						}
					}
					stream.closeEntry()
					entry = stream.nextEntry
				}
				result
			}
		}
		availableEntries.value = BackupSection.entries.mapNotNull { entry ->
			if (entry == BackupSection.INDEX || entry !in sections) {
				return@mapNotNull null
			}
			BackupSectionModel(
				section = entry,
				isChecked = true,
				isEnabled = true,
			)
		}
	}

	fun onItemClick(item: BackupSectionModel) {
		val map = availableEntries.value.associateByTo(EnumMap(BackupSection::class.java)) { it.section }
		map[item.section] = item.copy(isChecked = !item.isChecked)
		map.validate()
		availableEntries.value = map.values.sortedBy { it.section.ordinal }
	}

	fun getCheckedSections(): Set<BackupSection> = availableEntries.value
		.mapNotNullTo(EnumSet.noneOf(BackupSection::class.java)) {
			if (it.isChecked) it.section else null
		}

	/**
	 * Favourites need their categories and reading stats need their history: a section is only
	 * selectable while the one it depends on is checked.
	 */
	private fun MutableMap<BackupSection, BackupSectionModel>.validate() {
		requires(BackupSection.FAVOURITES, BackupSection.CATEGORIES)
		requires(BackupSection.STATS, BackupSection.HISTORY)
	}

	private fun MutableMap<BackupSection, BackupSectionModel>.requires(section: BackupSection, dependency: BackupSection) {
		val model = this[section] ?: return
		if (this[dependency]?.isChecked == true) {
			if (!model.isEnabled) {
				this[section] = model.copy(isEnabled = true)
			}
		} else if (model.isEnabled) {
			this[section] = model.copy(isEnabled = false, isChecked = false)
		}
	}

	private fun InputStream.readIndexDate(): Date? = runCatching {
		val json = Json {
			ignoreUnknownKeys = true
			coerceInputValues = true
		}
		val list = json.decodeFromString<List<BackupIndex>>(readBytes().decodeToString())
		list.firstOrNull()?.createdAt?.let(::Date)
	}.onFailure { e ->
		e.printStackTraceDebug()
	}.getOrNull()
}
