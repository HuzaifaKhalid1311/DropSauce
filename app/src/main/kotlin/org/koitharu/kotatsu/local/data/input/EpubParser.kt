package org.koitharu.kotatsu.local.data.input

import android.util.Xml
import org.jetbrains.annotations.Blocking
import org.koitharu.kotatsu.parsers.util.toTitleCase
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.zip.ZipFile

class EpubBook(
	val title: String?,
	val authors: Set<String>,
	val description: String?,
	val coverHref: String?,
	val spine: List<SpineItem>,
	val toc: List<TocItem>,
) {

	class SpineItem(
		val href: String,
		val title: String,
	)

	class TocItem(
		val title: String,
		val href: String,
		val level: Int,
	)
}

/**
 * Minimal EPUB 2/3 parser: container.xml -> OPF (metadata, manifest, spine) -> NCX or nav TOC.
 * All hrefs are zip entry paths, resolved relative to the OPF directory.
 */
object EpubParser {

	private const val RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"

	@Blocking
	fun parse(file: File): EpubBook = ZipFile(file).use { zip ->
		val containerXml = zip.readEntry("META-INF/container.xml")
			?: error("Not an EPUB: missing META-INF/container.xml in ${file.name}")
		val opfPath = parseContainer(containerXml) ?: error("No rootfile in container.xml")
		val opfDir = opfPath.substringBeforeLast('/', "")
		val opfXml = zip.readEntry(opfPath) ?: error("Missing OPF: $opfPath")
		val opf = parseOpf(opfXml)

		val resolve = { href: String -> resolveHref(opfDir, href) }

		// TOC: prefer NCX (present in most EPUB 2 and 3 files), fallback to EPUB 3 nav document
		val ncxHref = opf.manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.href
			?: opf.tocId?.let { opf.manifest[it]?.href }
		val navHref = opf.manifest.values.firstOrNull { "nav" in it.properties }?.href
		val toc: List<EpubBook.TocItem> = runCatching {
			when {
				ncxHref != null -> zip.readEntry(resolve(ncxHref))?.let { parseNcx(it, opfDir) }
				navHref != null -> zip.readEntry(resolve(navHref))?.let { parseNav(it, opfDir, resolve(navHref)) }
				else -> null
			}
		}.getOrNull().orEmpty()
		val labels = HashMap<String, String>(toc.size)
		for (item in toc) {
			labels.putIfAbsent(item.href, item.title)
		}

		val spine = ArrayList<EpubBook.SpineItem>(opf.spine.size)
		var untitledCount = 0
		var lastTitle: String? = null
		for (idref in opf.spine) {
			val href = opf.manifest[idref]?.href?.let(resolve) ?: continue
			if (href == navHref?.let(resolve)) {
				continue // don't show the nav document as a reading chapter
			}
			val label = labels[href]
			val title = when {
				label != null -> {
					lastTitle = label
					untitledCount = 0
					label
				}

				lastTitle != null -> "$lastTitle (${++untitledCount + 1})"
				else -> href.substringAfterLast('/')
					.substringBeforeLast('.')
					.replace('_', ' ')
					.toTitleCase()
			}
			spine.add(EpubBook.SpineItem(href, title))
		}

		val coverHref = opf.coverId?.let { opf.manifest[it]?.href }
			?: opf.manifest.values.firstOrNull { "cover-image" in it.properties }?.href
			?: opf.manifest.values.firstOrNull {
				it.mediaType.startsWith("image/") && ("cover" in it.id.lowercase() || "cover" in it.href.lowercase())
			}?.href

		EpubBook(
			title = opf.title,
			authors = opf.authors,
			description = opf.description,
			coverHref = coverHref?.let(resolve),
			spine = spine,
			toc = toc,
		)
	}

	private fun ZipFile.readEntry(name: String): String? {
		val entry = getEntry(name) ?: getEntry(name.removePrefix("/")) ?: return null
		return getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
	}

	private fun newParser(xml: String): XmlPullParser {
		val parser = Xml.newPullParser()
		runCatching { parser.setFeature(RELAXED, true) }
		parser.setInput(normalizeXmlInput(xml).reader())
		return parser
	}

	internal fun normalizeXmlInput(xml: String): String = xml.removePrefix("\uFEFF")

	private fun XmlPullParser.tag(): String = name.orEmpty().substringAfterLast(':').lowercase()

	private fun XmlPullParser.attr(name: String): String? {
		for (i in 0 until attributeCount) {
			if (getAttributeName(i).substringAfterLast(':').equals(name, ignoreCase = true)) {
				return getAttributeValue(i)
			}
		}
		return null
	}

	private fun parseContainer(xml: String): String? {
		val parser = newParser(xml)
		while (parser.next() != XmlPullParser.END_DOCUMENT) {
			if (parser.eventType == XmlPullParser.START_TAG && parser.tag() == "rootfile") {
				return parser.attr("full-path")
			}
		}
		return null
	}

	private class ManifestItem(val id: String, val href: String, val mediaType: String, val properties: String)

	private class Opf(
		val title: String?,
		val authors: Set<String>,
		val description: String?,
		val coverId: String?,
		val tocId: String?,
		val manifest: Map<String, ManifestItem>,
		val spine: List<String>,
	)

	private fun parseOpf(xml: String): Opf {
		val parser = newParser(xml)
		var title: String? = null
		val authors = LinkedHashSet<String>()
		var description: String? = null
		var coverId: String? = null
		var tocId: String? = null
		val manifest = LinkedHashMap<String, ManifestItem>()
		val spine = ArrayList<String>()
		while (parser.next() != XmlPullParser.END_DOCUMENT) {
			if (parser.eventType != XmlPullParser.START_TAG) continue
			when (parser.tag()) {
				"title" -> if (title == null) title = parser.nextTextSafe()
				"creator" -> parser.nextTextSafe()?.let { authors.add(it) }
				"description" -> if (description == null) description = parser.nextTextSafe()
				"meta" -> if (parser.attr("name") == "cover") {
					coverId = parser.attr("content")
				}

				"item" -> {
					val id = parser.attr("id") ?: continue
					val href = parser.attr("href") ?: continue
					manifest[id] = ManifestItem(
						id = id,
						href = href,
						mediaType = parser.attr("media-type").orEmpty(),
						properties = parser.attr("properties").orEmpty(),
					)
				}

				"spine" -> if (tocId == null) tocId = parser.attr("toc")
				"itemref" -> if (!parser.attr("linear").equals("no", ignoreCase = true)) {
					parser.attr("idref")?.let { spine.add(it) }
				}
			}
		}
		return Opf(title?.trim(), authors, description?.trim(), coverId, tocId, manifest, spine)
	}

	// read the full text of the current element, flattening any child markup. nextText() throws on
	// mixed content (common in <dc:description>, which often wraps HTML), so walk to the END_TAG
	private fun XmlPullParser.nextTextSafe(): String? {
		if (eventType != XmlPullParser.START_TAG) return null
		val sb = StringBuilder()
		var depth = 1
		while (depth > 0) {
			when (next()) {
				XmlPullParser.TEXT, XmlPullParser.CDSECT -> sb.append(text)
				XmlPullParser.START_TAG -> depth++
				XmlPullParser.END_TAG -> depth--
				XmlPullParser.END_DOCUMENT -> break
			}
		}
		return sb.toString().trim().takeIf { it.isNotBlank() }
	}

	private fun parseNcx(xml: String, opfDir: String): List<EpubBook.TocItem> {
		val parser = newParser(xml)
		val result = ArrayList<EpubBook.TocItem>()
		var depth = -1
		var label: String? = null
		var inNavLabel = false
		while (parser.next() != XmlPullParser.END_DOCUMENT) {
			when (parser.eventType) {
				XmlPullParser.START_TAG -> when (parser.tag()) {
					"navpoint" -> {
						depth++
						label = null
					}

					"navlabel" -> inNavLabel = true
					"text" -> if (inNavLabel && label == null) label = parser.nextTextSafe()
					"content" -> {
						val src = parser.attr("src")
						if (src != null && label != null && depth >= 0) {
							result.add(
								EpubBook.TocItem(
									title = label.trim(),
									href = resolveHref(opfDir, src.substringBefore('#')),
									level = depth,
								),
							)
						}
					}
				}

				XmlPullParser.END_TAG -> when (parser.tag()) {
					"navpoint" -> depth--
					"navlabel" -> inNavLabel = false
				}
			}
		}
		return result
	}

	private fun parseNav(xml: String, opfDir: String, navPath: String): List<EpubBook.TocItem> {
		val parser = newParser(xml)
		val result = ArrayList<EpubBook.TocItem>()
		val navDir = navPath.substringBeforeLast('/', "")
		var inToc = false
		var navDepth = 0
		var listDepth = -1
		while (parser.next() != XmlPullParser.END_DOCUMENT) {
			when (parser.eventType) {
				XmlPullParser.START_TAG -> when (parser.tag()) {
					"nav" -> if (!inToc) {
						navDepth++
						val type = parser.attr("type")
						// first nav is the TOC by convention; epub:type="toc" is explicit
						if (type == "toc" || type == null) {
							inToc = true
							navDepth = 1
						}
					}

					"ol", "ul" -> if (inToc) listDepth++
					"a" -> if (inToc) {
						val href = parser.attr("href")?.substringBefore('#')
						val text = parser.nextTextSafe()
						if (!href.isNullOrEmpty() && text != null) {
							result.add(
								EpubBook.TocItem(
									title = text.trim(),
									// hrefs in the nav doc are relative to the nav doc itself
									href = resolveHref(navDir.ifEmpty { opfDir }, href),
									level = listDepth.coerceAtLeast(0),
								),
							)
						}
					}
				}

				XmlPullParser.END_TAG -> when (parser.tag()) {
					"nav" -> if (inToc) {
						navDepth--
						if (navDepth <= 0) return result
					}

					"ol", "ul" -> if (inToc) listDepth--
				}
			}
		}
		return result
	}

	/** Resolves a content href against [baseDir], yielding a zip entry path with no leading slash. */
	internal fun resolveHref(baseDir: String, href: String): String {
		val decoded = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
		val segments = ArrayList<String>()
		if (baseDir.isNotEmpty() && !decoded.startsWith('/')) {
			segments.addAll(baseDir.split('/'))
		}
		for (part in decoded.trimStart('/').split('/')) {
			when (part) {
				"", "." -> Unit
				".." -> segments.removeLastOrNull()
				else -> segments.add(part)
			}
		}
		return segments.joinToString("/")
	}
}
