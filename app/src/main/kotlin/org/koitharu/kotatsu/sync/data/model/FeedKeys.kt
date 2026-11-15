package org.koitharu.kotatsu.sync.data.model

/** Identity of an updates-feed entry; shared by sync and local backups (to skip duplicates). */
object FeedKeys {

	/** Chapter ids when known, normalized titles for entries written before ids were stored. */
	fun of(mangaId: Long, chapterIds: String, chapters: String): String {
		val ids = chapterIds.lineSequence().map(String::trim).filter(String::isNotEmpty).toSortedSet()
		return if (ids.isNotEmpty()) {
			"$mangaId:" + ids.joinToString(",")
		} else {
			"$mangaId:t:" + chapters.lineSequence()
				.map { it.trim().lowercase() }
				.filter(String::isNotEmpty)
				.toSortedSet()
				.joinToString("\n")
		}
	}

	/** Parses the raw `manga_id:ids-or-t:titles` key written by the feed triggers. */
	fun ofRaw(raw: String): String? {
		val sep = raw.indexOf(':', startIndex = 1)
		if (sep < 0) return null
		val mangaId = raw.substring(0, sep).toLongOrNull() ?: return null
		val rest = raw.substring(sep + 1)
		return if (rest.startsWith("t:")) of(mangaId, "", rest.substring(2)) else of(mangaId, rest, "")
	}

	fun chapterIds(chapterIds: String): Set<Long> =
		chapterIds.lineSequence().mapNotNullTo(HashSet()) { it.trim().toLongOrNull() }
}
