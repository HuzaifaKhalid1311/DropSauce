package org.koitharu.kotatsu.core.util

class AlphanumComparator : Comparator<String> {

	override fun compare(s1: String?, s2: String?): Int {
		if (s1 === s2) return 0
		if (s1 == null || s2 == null) {
			return 0
		}
		var thisMarker = 0
		var thatMarker = 0
		val s1Length = s1.length
		val s2Length = s2.length
		while (thisMarker < s1Length && thatMarker < s2Length) {
			val thisChunkEnd = getChunkEnd(s1, s1Length, thisMarker)
			val thatChunkEnd = getChunkEnd(s2, s2Length, thatMarker)

			val thisChunkLength = thisChunkEnd - thisMarker
			val thatChunkLength = thatChunkEnd - thatMarker

			val isThisDigit = s1[thisMarker].isDigit()
			val isThatDigit = s2[thatMarker].isDigit()

			var result: Int
			if (isThisDigit && isThatDigit) {
				// Simple chunk comparison by length
				result = thisChunkLength - thatChunkLength
				// If equal, the first different number counts
				if (result == 0) {
					for (i in 0 until thisChunkLength) {
						result = s1[thisMarker + i] - s2[thatMarker + i]
						if (result != 0) {
							return result
						}
					}
				}
			} else {
				// In-place lexicographical comparison of non-digit chunks
				val minLen = minOf(thisChunkLength, thatChunkLength)
				result = 0
				for (i in 0 until minLen) {
					result = s1[thisMarker + i].compareTo(s2[thatMarker + i])
					if (result != 0) {
						break
					}
				}
				if (result == 0) {
					result = thisChunkLength - thatChunkLength
				}
			}
			if (result != 0) return result

			thisMarker = thisChunkEnd
			thatMarker = thatChunkEnd
		}
		return s1Length - s2Length
	}

	private fun getChunkEnd(s: String, slength: Int, start: Int): Int {
		var marker = start
		val isDigit = s[marker].isDigit()
		marker++
		while (marker < slength && s[marker].isDigit() == isDigit) {
			marker++
		}
		return marker
	}
}
