package org.haziffe.dropsauce.core.db.entity

import org.haziffe.dropsauce.core.model.MangaSource
import org.haziffe.dropsauce.core.model.getStoredTitleOrNull
import org.haziffe.dropsauce.parsers.model.ContentRating
import org.haziffe.dropsauce.parsers.model.Manga
import org.haziffe.dropsauce.parsers.model.MangaChapter
import org.haziffe.dropsauce.parsers.model.MangaState
import org.haziffe.dropsauce.parsers.model.MangaTag
import org.haziffe.dropsauce.parsers.model.SortOrder
import org.haziffe.dropsauce.parsers.util.longHashCode
import org.haziffe.dropsauce.parsers.util.mapToSet
import org.haziffe.dropsauce.parsers.util.nullIfEmpty
import org.haziffe.dropsauce.parsers.util.toArraySet
import org.haziffe.dropsauce.parsers.util.toTitleCase

private const val VALUES_DIVIDER = '\n'

// Entity to model

fun TagEntity.toMangaTag() = MangaTag(
	key = this.key,
	title = this.title.toTitleCase(),
	source = MangaSource(this.source),
)

fun Collection<TagEntity>.toMangaTags() = mapToSet(TagEntity::toMangaTag)

fun Collection<TagEntity>.toMangaTagsList() = map(TagEntity::toMangaTag)

fun MangaEntity.toManga(tags: Set<MangaTag>, chapters: List<ChapterEntity>?) = Manga(
	id = this.id,
	title = this.title,
	altTitles = this.altTitles?.split(VALUES_DIVIDER)?.toArraySet().orEmpty(),
	state = this.state?.let { MangaState(it) },
	rating = this.rating,
	contentRating = ContentRating(this.contentRating)
		?: if (isNsfw) ContentRating.ADULT else null,
	url = this.url,
	publicUrl = this.publicUrl,
	coverUrl = this.coverUrl,
	largeCoverUrl = this.largeCoverUrl,
	authors = this.authors?.split(VALUES_DIVIDER)?.toArraySet().orEmpty(),
	source = MangaSource(this.source),
	tags = tags,
	chapters = chapters?.toMangaChapters(),
)

fun MangaWithTags.toManga(chapters: List<ChapterEntity>? = null) = manga.toManga(tags.toMangaTags(), chapters)

fun Collection<MangaWithTags>.toMangaList() = map { it.toManga() }

fun ChapterEntity.toMangaChapter() = MangaChapter(
	id = chapterId,
	title = title.nullIfEmpty(),
	number = number,
	volume = volume,
	url = url,
	scanlator = scanlator,
	uploadDate = uploadDate,
	branch = branch,
	source = MangaSource(source),
)

fun Collection<ChapterEntity>.toMangaChapters() = map { it.toMangaChapter() }

// Model to entity

fun Manga.toEntity() = MangaEntity(
	id = id,
	url = url,
	publicUrl = publicUrl,
	source = source.name,
	sourceTitle = source.getStoredTitleOrNull(),
	largeCoverUrl = largeCoverUrl,
	coverUrl = coverUrl.orEmpty(),
	altTitles = altTitles.joinToString(VALUES_DIVIDER.toString()),
	rating = rating,
	isNsfw = isNsfw,
	contentRating = contentRating?.name,
	state = state?.name,
	title = title,
	authors = authors.joinToString(VALUES_DIVIDER.toString()),
)

fun MangaTag.toEntity() = TagEntity(
	title = title,
	key = key,
	source = source.name,
	id = "${key}_${source.name}".longHashCode(),
	isPinned = false, // for future use
)

fun Collection<MangaTag>.toEntities() = map(MangaTag::toEntity)

fun Iterable<IndexedValue<MangaChapter>>.toEntities(mangaId: Long) = map { (index, chapter) ->
	ChapterEntity(
		chapterId = chapter.id,
		mangaId = mangaId,
		title = chapter.title.orEmpty(),
		number = chapter.number,
		volume = chapter.volume,
		url = chapter.url,
		scanlator = chapter.scanlator,
		uploadDate = chapter.uploadDate,
		branch = chapter.branch,
		source = chapter.source.name,
		index = index,
	)
}

// Other

fun SortOrder(name: String, fallback: SortOrder): SortOrder = runCatching {
	SortOrder.valueOf(name)
}.getOrDefault(fallback)

fun MangaState(name: String): MangaState? = runCatching {
	MangaState.valueOf(name)
}.getOrNull()

fun ContentRating(name: String?): ContentRating? = runCatching {
	ContentRating.valueOf(name ?: return@runCatching null)
}.getOrNull()
