package org.koitharu.kotatsu.core.db.entity

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation

data class MangaWithTags(
	@Embedded val manga: MangaEntity,
	@Relation(
		parentColumn = "manga_id",
		entityColumn = "tag_id",
		associateBy = Junction(MangaTagsEntity::class)
	)
	val tags: List<TagEntity>,
)

/** Matches the way a single-genre search does (`LIKE %genre%`), for every genre of a multi-genre search. */
fun MangaWithTags.hasAllTags(genres: Collection<String>): Boolean =
	genres.all { genre -> tags.any { it.title.contains(genre, ignoreCase = true) } }
