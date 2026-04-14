package org.koitharu.kotatsu.backup.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@Serializable
@SerialName("eu.kanade.tachiyomi.data.backup.models.Backup")
data class MihonBackupFallback(
    @ProtoNumber(1) val backupManga: List<MihonBackupManga> = emptyList(),
    @ProtoNumber(2) val backupCategories: List<MihonBackupCategory> = emptyList(),
    @ProtoNumber(101) val backupSources: List<MihonBackupSource> = emptyList(),
    @ProtoNumber(104) val backupPreferences: List<ByteArray> = emptyList(),
    @ProtoNumber(105) val backupSourcePreferences: List<ByteArray> = emptyList(),
    @ProtoNumber(106) val backupExtensionRepo: List<MihonBackupExtensionRepo> = emptyList(),
)

