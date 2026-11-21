package org.koitharu.kotatsu.local.data.input

import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.core.util.ext.printStackTraceDebug
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Reads a PDF's own table of contents (its outline), which the platform renderer has no call for.
 * Just enough of a PDF reader for that: the cross-reference table or stream, compressed object streams,
 * the outline items and the page tree. Anything it can't follow (encrypted or broken files) gives an empty list.
 */
class PdfOutline private constructor(private val file: RandomAccessFile) {

	/** A top-level entry of the contents and the page index it opens at. */
	class Entry(val title: String, val pageIndex: Int)

	private data class Ref(val num: Int)
	private data class Name(val value: String)
	private class Text(val bytes: ByteArray)
	private class Stream(val dict: Map<*, *>, val data: ByteArray)

	private val offsets = HashMap<Int, Long>()
	private val compressed = HashMap<Int, Pair<Int, Int>>() // object number to (object stream, index in it)
	private val objects = HashMap<Int, Any?>()
	private val objectStreams = HashMap<Int, Map<Int, Any?>>()
	private var trailer: Map<*, *> = emptyMap<String, Any?>()
	private var depth = 0

	private fun entries(): List<Entry> {
		readXref()
		if (trailer.containsKey("Encrypt")) return emptyList() // titles would be scrambled
		val catalog = resolve(trailer["Root"]) as? Map<*, *> ?: return emptyList()
		val outlines = resolve(catalog["Outlines"]) as? Map<*, *> ?: return emptyList()
		var level = siblings(outlines["First"])
		// a lone top entry is usually the book's own title wrapping the real chapters
		if (level.size == 1) siblings(level.single()["First"]).takeIf { it.isNotEmpty() }?.let { level = it }
		val pages = pageIndexes(catalog["Pages"])
		val names = namedDestinations(catalog)
		return level.mapNotNull { item ->
			val action = resolve(item["A"]) as? Map<*, *>
			val dest = item["Dest"] ?: action?.takeIf { (resolve(it["S"]) as? Name)?.value == "GoTo" }?.get("D")
			val page = destinationPage(dest, pages, names, 0) ?: return@mapNotNull null
			Entry(decodeText(resolve(item["Title"])), page)
		}
	}

	private fun siblings(first: Any?): List<Map<*, *>> {
		val result = ArrayList<Map<*, *>>()
		val seen = HashSet<Any?>()
		var ref = first
		while (ref != null && seen.add(ref) && result.size < MAX_ITEMS) {
			val item = resolve(ref) as? Map<*, *> ?: break
			result.add(item)
			ref = item["Next"]
		}
		return result
	}

	/** Object number of every page to its index, in reading order. */
	private fun pageIndexes(root: Any?): Map<Int, Int> {
		val result = HashMap<Int, Int>()
		val seen = HashSet<Any?>()
		var index = 0
		fun walk(ref: Any?, level: Int) {
			if (level > MAX_DEPTH || !seen.add(ref)) return
			val node = resolve(ref) as? Map<*, *> ?: return
			val kids = resolve(node["Kids"]) as? List<*>
			if (kids != null && (resolve(node["Type"]) as? Name)?.value != "Page") {
				kids.forEach { walk(it, level + 1) }
			} else {
				if (ref is Ref) result[ref.num] = index
				index++
			}
		}
		walk(root, 0)
		return result
	}

	/** Destinations given by name, from the old `/Dests` dictionary and the `/Names` tree. */
	private fun namedDestinations(catalog: Map<*, *>): Map<String, Any?> {
		val result = HashMap<String, Any?>()
		(resolve(catalog["Dests"]) as? Map<*, *>)?.forEach { (key, value) -> result[key.toString()] = value }
		val seen = HashSet<Any?>()
		fun walk(ref: Any?, level: Int) {
			if (level > MAX_DEPTH || !seen.add(ref)) return
			val node = resolve(ref) as? Map<*, *> ?: return
			(resolve(node["Names"]) as? List<*>)?.chunked(2)?.forEach { pair ->
				val key = keyOf(resolve(pair.first())) ?: return@forEach
				result[key] = pair.getOrNull(1)
			}
			(resolve(node["Kids"]) as? List<*>)?.forEach { walk(it, level + 1) }
		}
		walk((resolve(catalog["Names"]) as? Map<*, *>)?.get("Dests"), 0)
		return result
	}

	private fun keyOf(value: Any?): String? = when (value) {
		is Name -> value.value
		is Text -> String(value.bytes, Charsets.ISO_8859_1)
		else -> null
	}

	private fun destinationPage(dest: Any?, pages: Map<Int, Int>, names: Map<String, Any?>, level: Int): Int? {
		if (level > 4) return null
		return when (val value = resolve(dest)) {
			is List<*> -> when (val target = value.firstOrNull()) {
				is Ref -> pages[target.num]
				is Number -> target.toInt()
				else -> null
			}

			is Map<*, *> -> destinationPage(value["D"], pages, names, level + 1)
			is Name, is Text -> destinationPage(names[keyOf(value)], pages, names, level + 1)
			else -> null
		}
	}

	private fun decodeText(value: Any?): String {
		val bytes = (value as? Text)?.bytes ?: return ""
		val text = when {
			bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
				String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)

			bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
				String(bytes, 3, bytes.size - 3, Charsets.UTF_8)

			else -> String(bytes, Charsets.ISO_8859_1) // PDFDocEncoding, close enough for titles
		}
		return text.replace(Regex("\\s+"), " ").trim()
	}

	// region objects

	private fun resolve(value: Any?): Any? {
		if (value !is Ref) return value
		if (depth > MAX_DEPTH) return null
		depth++
		try {
			return getObject(value.num)
		} finally {
			depth--
		}
	}

	private fun getObject(num: Int): Any? {
		if (objects.containsKey(num)) return objects[num]
		objects[num] = null // a reference loop reads as null
		val value = compressed[num]?.let { (stream, _) -> objectStream(stream)[num] }
			?: offsets[num]?.let { readObjectAt(it) }
		objects[num] = value
		return value
	}

	private fun objectStream(num: Int): Map<Int, Any?> = objectStreams.getOrPut(num) {
		val stream = getObject(num) as? Stream ?: return@getOrPut emptyMap()
		val count = (stream.dict["N"] as? Number)?.toInt() ?: return@getOrPut emptyMap()
		val first = (stream.dict["First"] as? Number)?.toInt() ?: return@getOrPut emptyMap()
		val header = Parser(stream.data)
		val positions = List(count) { (header.parse() as Number).toInt() to (header.parse() as Number).toInt() }
		positions.associate { (objectNum, offset) ->
			objectNum to runCatching { Parser(stream.data, first + offset).parse() }.getOrNull()
		}
	}

	private fun readObjectAt(offset: Long): Any? {
		var size = 4096
		while (true) {
			val buffer = readBytes(offset, size)
			try {
				val parser = Parser(buffer)
				parser.parse() // object number
				parser.parse() // generation
				parser.parse() // obj
				val value = parser.parse()
				if (value is Map<*, *> && parser.skipKeyword("stream")) {
					if (parser.peek() == '\r'.code) parser.pos++
					if (parser.peek() == '\n'.code) parser.pos++
					val length = (resolve(value["Length"]) as? Number)?.toInt() ?: return null
					return Stream(value, decode(value, readBytes(offset + parser.pos, length)))
				}
				return value
			} catch (e: EOFException) {
				if (buffer.size < size || size >= MAX_OBJECT_SIZE) throw e
				size *= 4
			}
		}
	}

	private fun readBytes(offset: Long, size: Int): ByteArray {
		val length = minOf(size.toLong(), file.length() - offset).coerceAtLeast(0).toInt()
		val buffer = ByteArray(length)
		file.seek(offset)
		file.readFully(buffer)
		return buffer
	}

	private fun decode(dict: Map<*, *>, data: ByteArray): ByteArray {
		val filters = when (val filter = resolve(dict["Filter"])) {
			is List<*> -> filter.map { resolve(it) }
			null -> emptyList()
			else -> listOf(filter)
		}
		var result = data
		for (filter in filters) {
			result = when ((filter as? Name)?.value) {
				"FlateDecode", "Fl" -> inflate(result)
				else -> throw IOException("Unsupported filter $filter")
			}
		}
		val params = when (val p = resolve(dict["DecodeParms"])) {
			is List<*> -> resolve(p.firstOrNull())
			else -> p
		} as? Map<*, *>
		val predictor = (params?.get("Predictor") as? Number)?.toInt() ?: 1
		if (predictor >= 10) {
			val colors = (params?.get("Colors") as? Number)?.toInt() ?: 1
			val bits = (params?.get("BitsPerComponent") as? Number)?.toInt() ?: 8
			val columns = (params?.get("Columns") as? Number)?.toInt() ?: 1
			result = undoPngPredictor(result, (columns * colors * bits + 7) / 8, ((colors * bits + 7) / 8).coerceAtLeast(1))
		}
		return result
	}

	private fun inflate(data: ByteArray): ByteArray {
		val inflater = Inflater()
		inflater.setInput(data)
		val out = ByteArrayOutputStream(data.size * 4)
		val chunk = ByteArray(16 * 1024)
		try {
			while (!inflater.finished()) {
				val n = inflater.inflate(chunk)
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
				out.write(chunk, 0, n)
			}
		} catch (_: DataFormatException) {
			// keep what came out before the damage
		} finally {
			inflater.end()
		}
		return out.toByteArray()
	}

	private fun undoPngPredictor(data: ByteArray, rowLength: Int, pixelLength: Int): ByteArray {
		val out = ByteArrayOutputStream(data.size)
		var previous = ByteArray(rowLength)
		var i = 0
		while (i + 1 + rowLength <= data.size) {
			val type = data[i].toInt()
			val row = data.copyOfRange(i + 1, i + 1 + rowLength)
			for (x in 0 until rowLength) {
				val a = if (x >= pixelLength) row[x - pixelLength].toInt() and 0xFF else 0
				val b = previous[x].toInt() and 0xFF
				val c = if (x >= pixelLength) previous[x - pixelLength].toInt() and 0xFF else 0
				val add = when (type) {
					1 -> a
					2 -> b
					3 -> (a + b) / 2
					4 -> {
						val p = a + b - c
						val pa = kotlin.math.abs(p - a)
						val pb = kotlin.math.abs(p - b)
						val pc = kotlin.math.abs(p - c)
						if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
					}

					else -> 0
				}
				row[x] = (row[x] + add).toByte()
			}
			out.write(row)
			previous = row
			i += rowLength + 1
		}
		return out.toByteArray()
	}

	// endregion

	// region cross-reference

	private fun readXref() {
		val tailSize = minOf(file.length(), TAIL_SIZE.toLong()).toInt()
		val tail = readBytes(file.length() - tailSize, tailSize)
		val at = String(tail, Charsets.ISO_8859_1).lastIndexOf("startxref")
		if (at < 0) throw IOException("No startxref")
		var offset: Long? = (Parser(tail, at + 9).parse() as Number).toLong()
		val seen = HashSet<Long>()
		var isNewest = true
		while (offset != null && offset > 0 && seen.add(offset)) {
			val dict = readXrefSection(offset)
			if (isNewest) {
				trailer = dict
				isNewest = false
			}
			(dict["XRefStm"] as? Number)?.let { readXrefSection(it.toLong()) } // hybrid files
			offset = (dict["Prev"] as? Number)?.toLong()
		}
	}

	// newer sections are read first, so an object already seen keeps its newest place
	private fun readXrefSection(offset: Long): Map<*, *> {
		val head = String(readBytes(offset, 4), Charsets.ISO_8859_1)
		return if (head == "xref") readXrefTable(offset) else readXrefStream(offset)
	}

	private fun readXrefTable(offset: Long): Map<*, *> {
		var size = 64 * 1024
		while (true) {
			val buffer = readBytes(offset, size)
			try {
				val parser = Parser(buffer, 4)
				while (!parser.skipKeyword("trailer")) {
					val start = (parser.parse() as Number).toInt()
					val count = (parser.parse() as Number).toInt()
					for (n in start until start + count) {
						val objectOffset = (parser.parse() as Number).toLong()
						parser.parse() // generation
						val type = parser.parse()
						if (type == Keyword("n") && n !in compressed) offsets.putIfAbsent(n, objectOffset)
					}
				}
				return parser.parse() as? Map<*, *> ?: emptyMap<String, Any?>()
			} catch (e: EOFException) {
				if (buffer.size < size) throw e
				size *= 4
			}
		}
	}

	private fun readXrefStream(offset: Long): Map<*, *> {
		val stream = readObjectAt(offset) as? Stream ?: throw IOException("Bad xref at $offset")
		val widths = (stream.dict["W"] as? List<*>)?.map { (it as Number).toInt() } ?: throw IOException("No /W")
		val ranges = (stream.dict["Index"] as? List<*>)?.map { (it as Number).toInt() }
			?: listOf(0, (stream.dict["Size"] as Number).toInt())
		val data = stream.data
		var pos = 0
		fun field(width: Int, default: Long): Long {
			if (width == 0) return default
			var value = 0L
			repeat(width) { value = (value shl 8) or (data[pos++].toLong() and 0xFF) }
			return value
		}
		for ((start, count) in ranges.chunked(2).map { it[0] to it[1] }) {
			for (n in start until start + count) {
				if (pos + widths.sum() > data.size) break
				val type = field(widths[0], 1)
				val second = field(widths[1], 0)
				val third = field(widths[2], 0)
				if (n in offsets || n in compressed) continue
				when (type) {
					1L -> offsets[n] = second
					2L -> compressed[n] = second.toInt() to third.toInt()
				}
			}
		}
		return stream.dict
	}

	// endregion

	private data class Keyword(val value: String)

	/** Reads PDF objects out of [buffer]; running off its end throws [EOFException]. */
	private class Parser(val buffer: ByteArray, var pos: Int = 0) {

		fun peek(): Int = if (pos < buffer.size) buffer[pos].toInt() and 0xFF else -1

		private fun next(): Int = if (pos < buffer.size) buffer[pos++].toInt() and 0xFF else throw EOFException()

		fun skipSpace() {
			while (pos < buffer.size) {
				val c = peek()
				if (c == '%'.code) {
					while (pos < buffer.size && peek() != '\n'.code && peek() != '\r'.code) pos++
				} else if (isSpace(c)) {
					pos++
				} else {
					return
				}
			}
		}

		fun skipKeyword(word: String): Boolean {
			skipSpace()
			if (pos + word.length > buffer.size) throw EOFException()
			for (i in word.indices) if (buffer[pos + i].toInt() != word[i].code) return false
			pos += word.length
			return true
		}

		fun parse(): Any? {
			skipSpace()
			val c = peek()
			return when {
				c == -1 -> throw EOFException()
				c == '<'.code && buffer.getOrNull(pos + 1)?.toInt() == '<'.code -> parseDict()
				c == '<'.code -> parseHex()
				c == '('.code -> parseLiteral()
				c == '['.code -> parseArray()
				c == '/'.code -> parseName()
				c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> parseNumberOrRef()
				else -> when (val word = readWord()) {
					"true" -> true
					"false" -> false
					"null" -> null
					else -> Keyword(word)
				}
			}
		}

		private fun parseDict(): Map<String, Any?> {
			pos += 2
			val result = HashMap<String, Any?>()
			while (true) {
				skipSpace()
				when (peek()) {
					-1 -> throw EOFException()
					'>'.code -> {
						pos += 2
						return result
					}
				}
				val key = parse() as? Name ?: continue
				result[key.value] = parse()
			}
		}

		private fun parseArray(): List<Any?> {
			pos++
			val result = ArrayList<Any?>()
			while (true) {
				skipSpace()
				when (peek()) {
					-1 -> throw EOFException()
					']'.code -> {
						pos++
						return result
					}
				}
				result.add(parse())
			}
		}

		private fun parseName(): Name {
			pos++
			val out = ByteArrayOutputStream()
			while (pos < buffer.size && !isSpace(peek()) && !isDelimiter(peek())) {
				val c = next()
				if (c == '#'.code && pos + 1 < buffer.size) {
					val hex = String(buffer, pos, 2, Charsets.ISO_8859_1).toIntOrNull(16)
					if (hex != null) {
						out.write(hex)
						pos += 2
						continue
					}
				}
				out.write(c)
			}
			return Name(String(out.toByteArray(), Charsets.UTF_8))
		}

		private fun parseLiteral(): Text {
			pos++
			val out = ByteArrayOutputStream()
			var nesting = 1
			while (true) {
				when (val c = next()) {
					'('.code -> {
						nesting++
						out.write(c)
					}

					')'.code -> {
						if (--nesting == 0) return Text(out.toByteArray())
						out.write(c)
					}

					'\\'.code -> when (val e = next()) {
						'n'.code -> out.write('\n'.code)
						'r'.code -> out.write('\r'.code)
						't'.code -> out.write('\t'.code)
						'b'.code -> out.write(8)
						'f'.code -> out.write(12)
						'\r'.code -> if (peek() == '\n'.code) pos++
						'\n'.code -> Unit
						in '0'.code..'7'.code -> {
							var value = e - '0'.code
							repeat(2) {
								if (peek() in '0'.code..'7'.code) value = value * 8 + (next() - '0'.code)
							}
							out.write(value and 0xFF)
						}

						else -> out.write(e)
					}

					else -> out.write(c)
				}
			}
		}

		private fun parseHex(): Text {
			pos++
			val digits = StringBuilder()
			while (true) {
				val c = next()
				if (c == '>'.code) break
				if (Character.digit(c, 16) >= 0) digits.append(c.toChar())
			}
			if (digits.length % 2 == 1) digits.append('0')
			return Text(ByteArray(digits.length / 2) { digits.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
		}

		private fun parseNumberOrRef(): Any {
			val first = readWord()
			val integer = first.toLongOrNull() ?: return first.toDoubleOrNull() ?: 0
			val mark = pos
			// "12 0 R" is a reference to object 12
			skipSpace()
			if (peek() in '0'.code..'9'.code) {
				val second = readWord()
				skipSpace()
				if (second.toIntOrNull() != null && peek() == 'R'.code &&
					(pos + 1 >= buffer.size || isSpace(buffer[pos + 1].toInt() and 0xFF) || isDelimiter(buffer[pos + 1].toInt() and 0xFF))
				) {
					pos++
					return Ref(integer.toInt())
				}
			}
			pos = mark
			return if (integer in Int.MIN_VALUE..Int.MAX_VALUE) integer.toInt() else integer
		}

		private fun readWord(): String {
			val start = pos
			while (pos < buffer.size && !isSpace(peek()) && !isDelimiter(peek())) pos++
			if (pos == start) {
				if (pos >= buffer.size) throw EOFException()
				pos++ // a stray delimiter: step over it
			}
			return String(buffer, start, pos - start, Charsets.ISO_8859_1)
		}

		private fun isSpace(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

		private fun isDelimiter(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
			c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
	}

	companion object {

		private const val TAIL_SIZE = 2048
		private const val MAX_OBJECT_SIZE = 4 * 1024 * 1024
		private const val MAX_DEPTH = 64
		private const val MAX_ITEMS = 5000

		private var cachedKey: Triple<String, Long, Long>? = null
		private var cachedEntries: List<Entry> = emptyList()

		/** The top-level contents of [file]; empty when it has none or can't be read. */
		@Blocking
		@Synchronized
		fun read(file: File): List<Entry> {
			val key = Triple(file.absolutePath, file.lastModified(), file.length())
			if (key == cachedKey) return cachedEntries
			val entries = runCatching {
				RandomAccessFile(file, "r").use { PdfOutline(it).entries() }
			}.onFailure { it.printStackTraceDebug() }.getOrDefault(emptyList())
			cachedKey = key
			cachedEntries = entries
			return entries
		}
	}
}
