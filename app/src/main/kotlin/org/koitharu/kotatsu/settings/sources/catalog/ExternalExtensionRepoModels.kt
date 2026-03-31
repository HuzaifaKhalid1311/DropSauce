package org.haziffe.dropsauce.settings.sources.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExternalExtensionRepoEntry(
	@SerialName("name") val name: String,
	@SerialName("pkg") val packageName: String,
	@SerialName("apk") val apkName: String,
	@SerialName("lang") val lang: String? = null,
	@SerialName("code") val versionCode: Long,
	@SerialName("version") val versionName: String,
	@SerialName("nsfw") val isNsfw: Int = 0,
)
