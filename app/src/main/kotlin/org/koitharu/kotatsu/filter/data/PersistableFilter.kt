package org.haziffe.dropsauce.filter.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import org.haziffe.dropsauce.core.model.MangaSourceSerializer
import org.haziffe.dropsauce.parsers.model.MangaListFilter
import org.haziffe.dropsauce.parsers.model.MangaSource

@Serializable
@JsonIgnoreUnknownKeys
data class PersistableFilter(
    @SerialName("name")
    val name: String,
    @Serializable(with = MangaSourceSerializer::class)
    @SerialName("source")
    val source: MangaSource,
    @Serializable(with = MangaListFilterSerializer::class)
    @SerialName("filter")
    val filter: MangaListFilter,
) {

    val id: Int
        get() = name.hashCode()

    companion object {

        const val MAX_TITLE_LENGTH = 18
    }
}
