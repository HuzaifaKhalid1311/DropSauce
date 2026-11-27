package org.koitharu.kotatsu.scrobbling.mangaupdates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.LIST_STATUSES
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.STATUS_COMPLETE
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.STATUS_ON_HOLD
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.STATUS_READING
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.STATUS_UNFINISHED
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.STATUS_WISH_LIST
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.decodeCredentials
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.encodeCredentials
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.listIdOf
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.pageOf
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.seriesIdQueryOrNull
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.toApiRating
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.toRatingFraction
import org.koitharu.kotatsu.scrobbling.mangaupdates.data.toSeriesIdOrNull

class MangaUpdatesUtilsTest {

	@Test
	fun listIdsMatchTheApiNumbering() {
		assertEquals(0, listIdOf(STATUS_READING))
		assertEquals(1, listIdOf(STATUS_WISH_LIST))
		assertEquals(2, listIdOf(STATUS_COMPLETE))
		assertEquals(3, listIdOf(STATUS_UNFINISHED))
		assertEquals(4, listIdOf(STATUS_ON_HOLD))
		assertEquals(5, LIST_STATUSES.size)
	}

	@Test
	fun unknownStatusHasNoListId() {
		assertEquals(-1, listIdOf(null))
		assertEquals(-1, listIdOf("plan_to_read"))
	}

	@Test
	fun paginationIsOneBased() {
		assertEquals(1, pageOf(0))
		assertEquals(1, pageOf(24))
		assertEquals(2, pageOf(25))
		assertEquals(3, pageOf(50))
	}

	@Test
	fun idQueryIsRecognisedWithAnyCaseAndSpacing() {
		assertEquals("12345", "id:12345".seriesIdQueryOrNull())
		assertEquals("pb8ztfm", "  ID: pb8ztfm ".seriesIdQueryOrNull())
		assertNull("one piece".seriesIdQueryOrNull())
		assertNull("hid:123".seriesIdQueryOrNull())
	}

	@Test
	fun seriesIdAcceptsNumbersAndBase36() {
		assertEquals(55412123456L, "55412123456".toSeriesIdOrNull())
		assertEquals("pb8ztfm".toLong(36), "pb8ztfm".toSeriesIdOrNull())
		assertEquals("PB8ZTFM".toLong(36), "PB8ZTFM".toSeriesIdOrNull())
	}

	@Test
	fun seriesIdRejectsGarbage() {
		assertNull("".toSeriesIdOrNull())
		assertNull("not an id".toSeriesIdOrNull())
		assertNull("-5".toSeriesIdOrNull())
		assertNull("0".toSeriesIdOrNull())
	}

	@Test
	fun noRatingIsSentAsNull() {
		assertNull(0f.toApiRating())
		assertNull((-1f).toApiRating())
	}

	@Test
	fun ratingsAreWholeTenthsWithinTheSupportedRange() {
		assertEquals(7.5, 7.5f.toApiRating()!!, 0.0)
		assertEquals(7.3, 7.3f.toApiRating()!!, 0.0)
		assertEquals(10.0, 10f.toApiRating()!!, 0.0)
		assertEquals(1.0, 0.3f.toApiRating()!!, 0.0)
		assertEquals(10.0, 11f.toApiRating()!!, 0.0)
	}

	@Test
	fun apiRatingBecomesAFraction() {
		assertEquals(0f, null.toRatingFraction(), 0f)
		assertEquals(0.75f, 7.5.toRatingFraction(), 0.0001f)
		assertEquals(1f, 10.0.toRatingFraction(), 0f)
		assertEquals(1f, 12.0.toRatingFraction(), 0f)
	}

	@Test
	fun credentialsRoundTrip() {
		assertEquals("user" to "pass", decodeCredentials(encodeCredentials("user", "pass")))
		// the characters that made ';' a poor separator
		assertEquals("a;b c" to "p;ss:w rd", decodeCredentials(encodeCredentials("a;b c", "p;ss:w rd")))
	}

	@Test
	fun incompleteCredentialsAreRejected() {
		assertNull(decodeCredentials("justausername"))
		assertNull(decodeCredentials(encodeCredentials("", "pass")))
		assertNull(decodeCredentials(encodeCredentials("user", "")))
	}
}
