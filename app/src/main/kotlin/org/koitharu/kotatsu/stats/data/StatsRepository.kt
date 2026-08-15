package org.koitharu.kotatsu.stats.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.koitharu.kotatsu.core.db.MangaDatabase
import org.koitharu.kotatsu.core.db.entity.toManga
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.stats.domain.StatsBucket
import org.koitharu.kotatsu.stats.domain.StatsBucketUnit
import org.koitharu.kotatsu.stats.domain.StatsPeriod
import org.koitharu.kotatsu.stats.domain.StatsRecord
import org.koitharu.kotatsu.stats.domain.ReadingStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import java.util.NavigableMap
import java.util.TreeMap
import java.util.TreeSet
import javax.inject.Inject

class StatsRepository @Inject constructor(
	private val settings: AppSettings,
	private val db: MangaDatabase,
) {

	/**
	 * One-pass snapshot for the statistics screen: the per-title breakdown, the activity chart
	 * buckets and every headline number, all derived from the same filtered set of sessions.
	 */
	suspend fun getStatsSnapshot(period: StatsPeriod, categories: Set<Long>): ReadingStats {
		val zone = ZoneId.systemDefault()
		val (unit, starts) = bucketStarts(period, zone)
		// The window is the chart's own first bar, not the raw "N days ago": anchoring bars to whole
		// hours/days/weeks means a plain `now - N days` cutoff would pull in sessions that fall before
		// bar zero, which then count towards the headline but have nowhere to be drawn.
		val fromDate = if (period == StatsPeriod.ALL) 0L else starts.first()
		val sessions = db.getStatsDao().getSessions(fromDate, categories)
		val durations = LongArray(starts.size)
		val activeDays = HashSet<LocalDate>()
		val perManga = HashMap<Long, MangaAggregate>()
		var total = 0L
		var chapterDuration = 0L
		var chapters = 0
		var pages = 0
		for (session in sessions) {
			total += session.duration
			chapters += session.chapters
			pages += session.pages
			if (session.chapters > 0) {
				chapterDuration += session.duration
			}
			val day = Instant.ofEpochMilli(session.startedAt).atZone(zone).toLocalDate()
			activeDays += day
			perManga.getOrPut(session.mangaId) { MangaAggregate() }.add(session, day)
			val index = starts.binarySearch(session.startedAt).let { if (it >= 0) it else -it - 2 }
			if (index in durations.indices) {
				durations[index] += session.duration
			}
		}
		val (records, others) = buildRecords(fromDate, categories, perManga, total)
		// Streaks are deliberately all-time — one that reset itself whenever you switch the period
		// filter would be meaningless — but they still honour the category filter like everything else.
		val allSessions = if (fromDate == 0L) sessions else db.getStatsDao().getSessions(0L, categories)
		val (currentStreak, longestStreak) = calculateStreaks(allSessions, zone)
		return ReadingStats(
			period = period,
			records = records,
			otherRecords = others,
			buckets = starts.mapIndexed { i, start -> StatsBucket(start, durations[i]) },
			bucketUnit = unit,
			totalDuration = total,
			chapterDuration = chapterDuration,
			chapters = chapters,
			pages = pages,
			activeDays = activeDays.size,
			currentStreak = currentStreak,
			longestStreak = longestStreak,
		)
	}

	/**
	 * Turns the per-manga totals into the ranked list. A title earns its own row while it is worth
	 * at least [OTHER_THRESHOLD] of the period and the list is under [MAX_TOP_RECORDS]; everything
	 * else collapses into a single trailing "other" row, whose titles are kept so the screen can
	 * show them on demand.
	 */
	private suspend fun buildRecords(
		fromDate: Long,
		categories: Set<Long>,
		perManga: Map<Long, MangaAggregate>,
		total: Long,
	): Pair<List<StatsRecord>, List<StatsRecord>> {
		if (perManga.isEmpty()) {
			return emptyList<StatsRecord>() to emptyList()
		}
		// Only this query knows the manga themselves; it is already ordered by time spent, descending.
		val entities = db.getStatsDao().getDurationStats(fromDate, categories)
		val result = ArrayList<StatsRecord>(entities.size)
		val others = ArrayList<StatsRecord>()
		var otherDuration = 0L
		var otherPages = 0
		var otherChapters = 0
		for ((entity, duration) in entities) {
			val aggregate = perManga[entity.id]
			val record = StatsRecord(
				manga = entity.toManga(emptySet(), null),
				duration = duration,
				pages = aggregate?.pages ?: 0,
				chapters = aggregate?.chapters ?: 0,
				daysRead = aggregate?.days?.size ?: 0,
				firstReadAt = aggregate?.firstReadAt ?: 0L,
			)
			val isTooSmall = total > 0L && duration.toDouble() / total < OTHER_THRESHOLD
			if (isTooSmall || result.size >= MAX_TOP_RECORDS) {
				others += record
				otherDuration += duration
				otherPages += record.pages
				otherChapters += record.chapters
			} else {
				result += record
			}
		}
		if (otherDuration != 0L) {
			result += StatsRecord(
				manga = null,
				duration = otherDuration,
				pages = otherPages,
				chapters = otherChapters,
				daysRead = 0,
				firstReadAt = others.minOfOrNull { it.firstReadAt } ?: 0L,
			)
		}
		return result to others
	}

	/**
	 * Bar boundaries for the activity chart, always ending on the current hour/day/week/month so the
	 * rightmost bar is "now". Longer periods aggregate harder — a five-year history as daily bars is
	 * unreadable, so "all time" is charted as the last twelve months while the numbers above it stay
	 * genuinely all-time.
	 */
	private fun bucketStarts(period: StatsPeriod, zone: ZoneId): Pair<StatsBucketUnit, LongArray> {
		val now = ZonedDateTime.now(zone)
		fun starts(count: Int, unit: StatsBucketUnit, anchor: ZonedDateTime, step: (ZonedDateTime, Long) -> ZonedDateTime) =
			unit to LongArray(count) { i -> step(anchor, -(count - 1L - i)).toInstant().toEpochMilli() }
		return when (period) {
			StatsPeriod.DAY -> starts(24, StatsBucketUnit.HOUR, now.truncatedTo(ChronoUnit.HOURS)) { a, d -> a.plusHours(d) }
			StatsPeriod.WEEK -> starts(7, StatsBucketUnit.DAY, now.toLocalDate().atStartOfDay(zone)) { a, d -> a.plusDays(d) }
			StatsPeriod.MONTH -> starts(30, StatsBucketUnit.DAY, now.toLocalDate().atStartOfDay(zone)) { a, d -> a.plusDays(d) }
			StatsPeriod.MONTHS_3 -> starts(
				13,
				StatsBucketUnit.WEEK,
				now.toLocalDate().with(WeekFields.of(Locale.getDefault()).dayOfWeek(), 1).atStartOfDay(zone),
			) { a, d -> a.plusWeeks(d) }

			StatsPeriod.ALL -> starts(
				12,
				StatsBucketUnit.MONTH,
				now.toLocalDate().withDayOfMonth(1).atStartOfDay(zone),
			) { a, d -> a.plusMonths(d) }
		}
	}

	/** Current and longest run of consecutive days with any reading on them, in the local zone. */
	private fun calculateStreaks(sessions: List<StatsEntity>, zone: ZoneId): Pair<Int, Int> {
		val days = sessions
			.mapTo(TreeSet()) { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }
		if (days.isEmpty()) {
			return 0 to 0
		}
		var longest = 0
		var run = 0
		var previous: LocalDate? = null
		for (day in days) {
			run = if (previous != null && previous.plusDays(1) == day) run + 1 else 1
			if (run > longest) longest = run
			previous = day
		}
		val today = LocalDate.now(zone)
		// A streak stays alive until the day after the last session ends, so reading yesterday but
		// not (yet) today still counts.
		val current = if (days.last() == today || days.last() == today.minusDays(1)) run else 0
		return current to longest
	}

	suspend fun getChapterReadingStats(): ChapterReadingStats = db.withTransaction {
		val dao = db.getStatsDao()
		ChapterReadingStats(
			totalDuration = dao.getTotalReadDurationWithChapters(),
			chapters = dao.getTotalReadChapters(),
		)
	}

	suspend fun getTotalPagesRead(mangaId: Long): Int {
		return db.getStatsDao().getReadPagesCount(mangaId)
	}

	suspend fun getMangaTimeline(mangaId: Long): NavigableMap<Long, Int> {
		val entities = db.getStatsDao().findAll(mangaId)
		val map = TreeMap<Long, Int>()
		for (e in entities) {
			map[e.startedAt] = e.pages
		}
		return map
	}

	suspend fun clearStats() {
		db.getStatsDao().clear()
	}

	fun observeHasStats(mangaId: Long): Flow<Boolean> = settings.observeAsFlow(AppSettings.KEY_STATS_ENABLED) {
		isStatsEnabled
	}.flatMapLatest { isEnabled ->
		if (isEnabled) {
			db.getStatsDao().observeRowCount(mangaId).map { it > 0 }
		} else {
			flowOf(false)
		}
	}.distinctUntilChanged()
}

/** Mutable running totals for one title while the period's sessions are being walked once. */
private class MangaAggregate {

	var pages: Int = 0
		private set
	var chapters: Int = 0
		private set
	var firstReadAt: Long = Long.MAX_VALUE
		private set
	val days = HashSet<LocalDate>()

	fun add(session: StatsEntity, day: LocalDate) {
		pages += session.pages
		chapters += session.chapters
		if (session.startedAt < firstReadAt) {
			firstReadAt = session.startedAt
		}
		days += day
	}
}

private const val OTHER_THRESHOLD = 0.01
private const val MAX_TOP_RECORDS = 10

data class ChapterReadingStats(
	val totalDuration: Long,
	val chapters: Int,
)
