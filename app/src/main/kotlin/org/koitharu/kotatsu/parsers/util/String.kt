@file:JvmName("StringUtils")

package org.koitharu.kotatsu.parsers.util

import androidx.collection.MutableIntList
import java.math.BigInteger
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.*
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract
import kotlin.math.min

private val REGEX_WHITESPACE = Regex("\\s+")
internal const val LONG_HASH_SEED = 1125899906842597L

public fun String.removeSurrounding(vararg chars: Char): String {
	if (isEmpty()) {
		return this
	}
	for (c in chars) {
		if (first() == c && last() == c) {
			return substring(1, length - 1)
		}
	}
	return this
}

public inline fun <C : R, R : CharSequence?> C?.ifNullOrEmpty(defaultValue: () -> R): R {
	contract {
		callsInPlace(defaultValue, InvocationKind.AT_MOST_ONCE)
	}
	return if (this.isNullOrEmpty()) defaultValue() else this
}

public fun String.longHashCode(): Long {
	var h = LONG_HASH_SEED
	val len: Int = this.length
	for (i in 0 until len) {
		h = 31 * h + this[i].code
	}
	return h
}

public fun String.toCamelCase(): String {
	if (isEmpty()) {
		return this
	}
	val result = StringBuilder(length)
	var capitalize = true
	for (char in this) {
		result.append(
			if (capitalize) {
				char.uppercase()
			} else {
				char.lowercase()
			},
		)
		capitalize = char.isWhitespace()
	}
	return result.toString()
}

public fun String.digits(): String = filter { it.isDigit() }

public fun String.toTitleCase(): String {
	return replaceFirstChar { x -> x.uppercase() }
}

public fun String.toTitleCase(locale: Locale): String {
	return replaceFirstChar { x -> x.uppercase(locale) }
}

public fun String.ellipsize(maxLength: Int): String = if (this.length > maxLength) {
	this.take(maxLength - 1) + Typography.ellipsis
} else this

public fun String.splitTwoParts(delimiter: Char): Pair<String, String>? {
	val indices = MutableIntList(4)
	for ((i, c) in this.withIndex()) {
		if (c == delimiter) {
			indices += i
		}
	}
	if (indices.isEmpty() || indices.size and 1 == 0) {
		return null
	}
	val index = indices[indices.size / 2]
	return substring(0, index) to substring(index + 1)
}

public fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())

public fun String.urlDecode(): String = URLDecoder.decode(this, Charsets.UTF_8.name())

public fun String.nl2br(): String = replace("\n", "<br>")

public fun String.splitByWhitespace(): List<String> = trim().split(REGEX_WHITESPACE)

public fun <T : CharSequence> T.nullIfEmpty(): T? = takeUnless { it.isEmpty() }

public fun ByteArray.byte2HexFormatted(): String {
	val str = StringBuilder(size * 2)
	for (i in indices) {
		var h = Integer.toHexString(this[i].toInt())
		val l = h.length
		if (l == 1) {
			h = "0$h"
		}
		if (l > 2) {
			h = h.substring(l - 2, l)
		}
		str.append(h.uppercase(Locale.ROOT))
		if (i < size - 1) {
			str.append(':')
		}
	}
	return str.toString()
}

public fun String.md5(): String {
	val md = MessageDigest.getInstance("MD5")
	return BigInteger(1, md.digest(toByteArray()))
		.toString(16)
		.padStart(32, '0')
}

public fun String.substringBetween(from: String, to: String, fallbackValue: String = this): String {
	val fromIndex = indexOf(from)
	if (fromIndex == -1) {
		return fallbackValue
	}
	val toIndex = lastIndexOf(to)
	return if (toIndex == -1) {
		fallbackValue
	} else {
		substring(fromIndex + from.length, toIndex)
	}
}

public fun String.substringBetweenFirst(from: String, to: String): String? {
	val fromIndex = indexOf(from)
	if (fromIndex == -1) {
		return null
	}
	val toIndex = indexOf(to, fromIndex)
	return if (toIndex == -1) {
		null
	} else {
		substring(fromIndex + from.length, toIndex)
	}
}

public fun String.substringBetweenLast(from: String, to: String, fallbackValue: String = this): String {
	val fromIndex = lastIndexOf(from)
	if (fromIndex == -1) {
		return fallbackValue
	}
	val toIndex = lastIndexOf(to)
	return if (toIndex == -1) {
		fallbackValue
	} else {
		substring(fromIndex + from.length, toIndex)
	}
}

public fun String.find(regex: Regex): String? = regex.find(this)?.value

public fun String.findGroupValue(regex: Regex): String? = regex.find(this)?.groupValues?.getOrNull(1)

public fun String.removeSuffix(suffix: Char): String {
	if (lastOrNull() == suffix) {
		return substring(0, length - 1)
	}
	return this
}

public fun String.levenshteinDistance(other: String): Int {
	if (this == other) {
		return 0
	}
	if (this.isEmpty()) {
		return other.length
	}
	if (other.isEmpty()) {
		return this.length
	}

	val (s1, s2) = if (length <= other.length) this to other else other to this
	val s1Length = s1.length
	val s2Length = s2.length

	var cost = IntArray(s1Length + 1) { it }
	var newCost = IntArray(s1Length + 1)

	for (i in 1..s2Length) {
		newCost[0] = i
		val c2 = s2[i - 1]

		for (j in 1..s1Length) {
			val match = if (s1[j - 1] == c2) 0 else 1

			val costReplace = cost[j - 1] + match
			val costInsert = cost[j] + 1
			val costDelete = newCost[j - 1] + 1

			newCost[j] = min(min(costInsert, costDelete), costReplace)
		}

		val swap = cost
		cost = newCost
		newCost = swap
	}

	return cost[s1Length]
}

/**
 * @param threshold 0 = exact match
 */
public fun String.almostEquals(other: String, threshold: Float): Boolean {
	if (threshold <= 0f) {
		return equals(other, ignoreCase = true)
	}
	if (equals(other, ignoreCase = true)) {
		return true
	}
	val avgLen = (length + other.length) / 2f
	val maxAllowedDiff = threshold * avgLen
	if (kotlin.math.abs(length - other.length) > maxAllowedDiff) {
		return false
	}
	val diff = caseInsensitiveLevenshteinDistance(other, maxAllowedDiff.toInt() + 1) / avgLen
	return diff < threshold
}

public fun String.caseInsensitiveLevenshteinDistance(other: String, maxDistance: Int = Int.MAX_VALUE): Int {
	if (equals(other, ignoreCase = true)) {
		return 0
	}
	val len1 = length
	val len2 = other.length
	if (len1 == 0) return len2
	if (len2 == 0) return len1
	if (kotlin.math.abs(len1 - len2) > maxDistance) {
		return maxDistance + 1
	}

	val (s1, s2) = if (len1 <= len2) this to other else other to this
	val s1Length = s1.length
	val s2Length = s2.length

	var cost = IntArray(s1Length + 1) { it }
	var newCost = IntArray(s1Length + 1)

	for (i in 1..s2Length) {
		newCost[0] = i
		val c2 = s2[i - 1]
		var minRowCost = newCost[0]

		for (j in 1..s1Length) {
			val match = if (s1[j - 1].equals(c2, ignoreCase = true)) 0 else 1

			val costReplace = cost[j - 1] + match
			val costInsert = cost[j] + 1
			val costDelete = newCost[j - 1] + 1

			val c = min(min(costInsert, costDelete), costReplace)
			newCost[j] = c
			if (c < minRowCost) {
				minRowCost = c
			}
		}

		if (minRowCost > maxDistance) {
			return maxDistance + 1
		}

		val swap = cost
		cost = newCost
		newCost = swap
	}

	return cost[s1Length]
}

public fun String.isNumeric(): Boolean = all { c -> c.isDigit() }

internal fun StringBuilder.removeTrailingZero() {
	if (length > 2 && get(length - 1) == '0') {
		val dot = get(length - 2)
		if (dot == ',' || dot == '.') {
			delete(length - 2, length)
		}
	}
}
