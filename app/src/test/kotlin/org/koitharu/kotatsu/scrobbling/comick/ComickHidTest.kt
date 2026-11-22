package org.koitharu.kotatsu.scrobbling.comick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.koitharu.kotatsu.scrobbling.comick.data.hidToLongOrNull
import org.koitharu.kotatsu.scrobbling.comick.data.toHid

class ComickHidTest {

	@Test
	fun roundTrip() {
		listOf("aB7kP2xQ", "rT4mN8vW", "AAAAAAAA", "______-_").forEach { hid ->
			assertEquals(hid, hid.hidToLongOrNull()!!.toHid())
		}
	}

	@Test
	fun rejectsOtherLengths() {
		assertNull("abc".hidToLongOrNull())
		assertNull("aB7kP2xQ9".hidToLongOrNull())
	}
}
