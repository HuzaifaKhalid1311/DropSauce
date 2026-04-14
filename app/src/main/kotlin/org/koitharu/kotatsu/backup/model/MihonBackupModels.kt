package org.koitharu.kotatsu.backup.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.Backup")
data class MihonBackup(
    @ProtoNumber(1) val backupManga: List<MihonBackupManga>,
    @ProtoNumber(2) var backupCategories: List<MihonBackupCategory> = emptyList(),
    @ProtoNumber(101) var backupSources: List<MihonBackupSource> = emptyList(),
    @ProtoNumber(104) var backupPreferences: List<MihonBackupPreference> = emptyList(),
    @ProtoNumber(105) var backupSourcePreferences: List<MihonBackupSourcePreferences> = emptyList(),
    @ProtoNumber(106) var backupExtensionRepo: List<MihonBackupExtensionRepo> = emptyList(),
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupManga")
data class MihonBackupManga(
    @ProtoNumber(1) var source: Long,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var title: String = "",
    @ProtoNumber(4) var artist: String? = null,
    @ProtoNumber(5) var author: String? = null,
    @ProtoNumber(6) var description: String? = null,
    @ProtoNumber(7) var genre: List<String> = emptyList(),
    @ProtoNumber(8) var status: Int = 0,
    @ProtoNumber(9) var thumbnailUrl: String? = null,
    @ProtoNumber(13) var dateAdded: Long = 0,
    @ProtoNumber(16) var chapters: List<MihonBackupChapter> = emptyList(),
    @ProtoNumber(17) var categories: List<Long> = emptyList(),
    @ProtoNumber(18) var tracking: List<MihonBackupTracking> = emptyList(),
    @ProtoNumber(100) var favorite: Boolean = true,
    @ProtoNumber(101) var chapterFlags: Int = 0,
    @ProtoNumber(103) var viewerFlags: Int? = null,
    @ProtoNumber(104) var history: List<MihonBackupHistory> = emptyList(),
    @ProtoNumber(105) var updateStrategy: Int = 0,
    @ProtoNumber(106) var lastModifiedAt: Long = 0,
    @ProtoNumber(107) var favoriteModifiedAt: Long? = null,
    @ProtoNumber(108) var excludedScanlators: List<String> = emptyList(),
    @ProtoNumber(109) var version: Long = 0,
    @ProtoNumber(110) var notes: String = "",
    @ProtoNumber(111) var initialized: Boolean = false,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupChapter")
data class MihonBackupChapter(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var scanlator: String? = null,
    @ProtoNumber(4) var read: Boolean = false,
    @ProtoNumber(5) var bookmark: Boolean = false,
    @ProtoNumber(6) var lastPageRead: Long = 0,
    @ProtoNumber(7) var dateFetch: Long = 0,
    @ProtoNumber(8) var dateUpload: Long = 0,
    @ProtoNumber(9) var chapterNumber: Float = 0f,
    @ProtoNumber(10) var sourceOrder: Long = 0,
    @ProtoNumber(11) var lastModifiedAt: Long = 0,
    @ProtoNumber(12) var version: Long = 0,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupHistory")
data class MihonBackupHistory(
    @ProtoNumber(1) var url: String,
    @ProtoNumber(2) var lastRead: Long,
    @ProtoNumber(3) var readDuration: Long = 0,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupCategory")
data class MihonBackupCategory(
    @ProtoNumber(1) var name: String,
    @ProtoNumber(2) var order: Long = 0,
    @ProtoNumber(3) var id: Long = 0,
    @ProtoNumber(100) var flags: Long = 0,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupTracking")
data class MihonBackupTracking(
    @ProtoNumber(1) var syncId: Int,
    @ProtoNumber(2) var libraryId: Long,
    @ProtoNumber(3) var mediaIdInt: Int = 0,
    @ProtoNumber(4) var trackingUrl: String = "",
    @ProtoNumber(5) var title: String = "",
    @ProtoNumber(6) var lastChapterRead: Float = 0f,
    @ProtoNumber(7) var totalChapters: Int = 0,
    @ProtoNumber(8) var score: Float = 0f,
    @ProtoNumber(9) var status: Int = 0,
    @ProtoNumber(10) var startedReadingDate: Long = 0,
    @ProtoNumber(11) var finishedReadingDate: Long = 0,
    @ProtoNumber(12) var private: Boolean = false,
    @ProtoNumber(100) var mediaId: Long = 0,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupSource")
data class MihonBackupSource(
    @ProtoNumber(1) var name: String = "",
    @ProtoNumber(2) var sourceId: Long,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupPreference")
data class MihonBackupPreference(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val value: MihonPreferenceValue,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences")
data class MihonBackupSourcePreferences(
    @ProtoNumber(1) val sourceKey: String,
    @ProtoNumber(2) val prefs: List<MihonBackupPreference>,
)

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.PreferenceValue")
sealed class MihonPreferenceValue

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue")
data class MihonIntPreferenceValue(val value: Int) : MihonPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue")
data class MihonLongPreferenceValue(val value: Long) : MihonPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue")
data class MihonFloatPreferenceValue(val value: Float) : MihonPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue")
data class MihonStringPreferenceValue(val value: String) : MihonPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue")
data class MihonBooleanPreferenceValue(val value: Boolean) : MihonPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue")
data class MihonStringSetPreferenceValue(val value: Set<String>) : MihonPreferenceValue()

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos")
data class MihonBackupExtensionRepo(
    @ProtoNumber(1) var baseUrl: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var shortName: String?,
    @ProtoNumber(4) var website: String,
    @ProtoNumber(5) var signingKeyFingerprint: String,
)

