package org.koitharu.kotatsu.local.data.output

import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koitharu.kotatsu.core.util.MimeTypes
import org.koitharu.kotatsu.core.util.ext.MimeType
import org.koitharu.kotatsu.core.util.ext.deleteAwait
import org.koitharu.kotatsu.core.zip.ZipOutput
import org.koitharu.kotatsu.local.data.MangaIndex
import org.koitharu.kotatsu.local.data.input.EpubParser
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import java.io.File
import java.util.zip.ZipFile

/**
 * Writes a downloaded web novel as an EPUB, so it reopens through the untouched [EpubParser] and the
 * same text reader that renders imported books.
 *
 * A "page" here is one chapter of html, which is why this exists alongside [LocalMangaZipOutput]
 * rather than branching inside it: the container needs a spine and metadata, not a flat image list.
 *
 * ponytail: illustrations stay as remote `<img>` urls instead of being embedded — the reader resolves
 * those when online. Embedding them means a second fetch pass per chapter for a rarely-used case.
 */
class LocalNovelEpubOutput(
	rootFile: File,
	private val manga: Manga,
) : LocalMangaOutput(rootFile) {

	private val output = ZipOutput(File(rootFile.path + SUFFIX_TMP))
	private val index = MangaIndex(null)
	private val mutex = Mutex()

	/** Spine order is the entry name, which sorts lexically into reading order by construction. */
	private val spine = sortedMapOf<String, String>()
	private var coverEntry: String? = null

	init {
		index.setMangaInfo(manga)
	}

	override suspend fun mergeWithExisting() = mutex.withLock {
		if (rootFile.exists()) {
			runInterruptible(Dispatchers.IO) { mergeWith(rootFile) }
		}
	}

	override suspend fun addCover(file: File, type: MimeType?) = mutex.withLock {
		val name = "$DIR_CONTENT/cover." + (MimeTypes.getExtension(type) ?: "jpg")
		runInterruptible(Dispatchers.IO) { output.put(name, file) }
		coverEntry = name
		index.setCoverEntry(name)
	}

	override suspend fun addPage(chapter: IndexedValue<MangaChapter>, file: File, pageNumber: Int, type: MimeType?) =
		mutex.withLock {
			val name = "$DIR_CONTENT/" + ENTRY_PATTERN.format(
				chapter.value.branch.hashCode(),
				chapter.index + 1,
			) + ".xhtml"
			val title = chapter.value.title ?: "Chapter ${chapter.index + 1}"
			runInterruptible(Dispatchers.IO) {
				output.put(name, wrapXhtml(title, file.readText()))
			}
			spine[name] = title
			// The entry name is what maps this chapter id back on read-back, and that mapping is what
			// links the download to the source's chapter list instead of a separate local book.
			index.addChapter(chapter, name)
		}

	override suspend fun flushChapter(chapter: MangaChapter): Boolean = false

	override suspend fun finish() = mutex.withLock {
		runInterruptible(Dispatchers.IO) {
			output.use { zip ->
				zip.put(ENTRY_MIMETYPE, MIMETYPE_EPUB)
				zip.put(ENTRY_CONTAINER, containerXml())
				zip.put("$DIR_CONTENT/$FILE_OPF", contentOpf())
				zip.put("$DIR_CONTENT/$FILE_NCX", tocNcx())
				zip.put(ENTRY_NAME_INDEX, index.toString())
				zip.finish()
			}
		}
		rootFile.deleteAwait()
		output.file.renameTo(rootFile)
		Unit
	}

	override suspend fun cleanup() = mutex.withLock {
		output.file.deleteAwait()
		Unit
	}

	override fun close() = output.close()

	/**
	 * Copies an earlier download's chapters across so a resumed or extended download keeps them. The
	 * generated container files are skipped — [finish] regenerates them from the merged spine.
	 */
	@WorkerThread
	private fun mergeWith(other: File) {
		val previousTitles = runCatching { EpubParser.parse(other).spine.associate { it.href to it.title } }
			.getOrDefault(emptyMap())
		ZipFile(other).use { zip ->
			for (entry in zip.entries()) {
				if (entry.isDirectory || entry.name in GENERATED_ENTRIES) continue
				if (entry.name == "$DIR_CONTENT/$FILE_OPF" || entry.name == "$DIR_CONTENT/$FILE_NCX") continue
				if (spine.containsKey(entry.name)) continue
				output.copyEntryFrom(zip, entry)
				if (entry.name.endsWith(".xhtml", ignoreCase = true)) {
					spine[entry.name] = previousTitles[entry.name] ?: entry.name.substringAfterLast('/')
				}
			}
			val previousIndex = zip.getEntry(ENTRY_NAME_INDEX)?.let {
				MangaIndex(zip.getInputStream(it).use { stream -> stream.reader().readText() })
			}
			if (previousIndex != null) {
				previousIndex.getMangaInfo()?.chapters?.withIndex()?.forEach { chapter ->
					index.addChapter(chapter, previousIndex.getChapterFileName(chapter.value.id))
				}
			}
		}
	}

	private fun wrapXhtml(title: String, body: String) = buildString {
		append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
		append("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>")
		append(title.escapeXml())
		append("</title></head><body>")
		append(body)
		append("</body></html>")
	}

	private fun containerXml() = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
	<rootfiles><rootfile full-path="$DIR_CONTENT/$FILE_OPF" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

	private fun contentOpf() = buildString {
		append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
		append("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"bookid\">")
		append("<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf\">")
		append("<dc:identifier id=\"bookid\">").append(manga.id.toString()).append("</dc:identifier>")
		append("<dc:title>").append(manga.title.escapeXml()).append("</dc:title>")
		append("<dc:language>und</dc:language>")
		manga.authors.forEach { append("<dc:creator>").append(it.escapeXml()).append("</dc:creator>") }
		manga.description?.let { append("<dc:description>").append(it.escapeXml()).append("</dc:description>") }
		if (coverEntry != null) append("<meta name=\"cover\" content=\"cover-image\"/>")
		append("</metadata><manifest>")
		append("<item id=\"ncx\" href=\"$FILE_NCX\" media-type=\"application/x-dtbncx+xml\"/>")
		coverEntry?.let {
			val media = MimeTypes.getMimeTypeFromExtension(it.substringAfterLast('.'))?.toString() ?: "image/jpeg"
			append("<item id=\"cover-image\" href=\"").append(it.substringAfterLast('/'))
				.append("\" media-type=\"").append(media).append("\" properties=\"cover-image\"/>")
		}
		spine.keys.forEachIndexed { i, entry ->
			append("<item id=\"c$i\" href=\"").append(entry.substringAfterLast('/'))
				.append("\" media-type=\"application/xhtml+xml\"/>")
		}
		append("</manifest><spine toc=\"ncx\">")
		spine.keys.indices.forEach { append("<itemref idref=\"c").append(it.toString()).append("\"/>") }
		append("</spine></package>")
	}

	private fun tocNcx() = buildString {
		append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
		append("<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">")
		append("<head><meta name=\"dtb:uid\" content=\"").append(manga.id.toString()).append("\"/></head>")
		append("<docTitle><text>").append(manga.title.escapeXml()).append("</text></docTitle><navMap>")
		spine.entries.forEachIndexed { i, (entry, title) ->
			append("<navPoint id=\"n$i\" playOrder=\"").append((i + 1).toString()).append("\">")
			append("<navLabel><text>").append(title.escapeXml()).append("</text></navLabel>")
			append("<content src=\"").append(entry.substringAfterLast('/')).append("\"/></navPoint>")
		}
		append("</navMap></ncx>")
	}

	private fun String.escapeXml() = replace("&", "&amp;")
		.replace("<", "&lt;")
		.replace(">", "&gt;")
		.replace("\"", "&quot;")

	companion object {

		private const val DIR_CONTENT = "OEBPS"
		private const val FILE_OPF = "content.opf"
		private const val FILE_NCX = "toc.ncx"
		private const val ENTRY_MIMETYPE = "mimetype"
		private const val ENTRY_CONTAINER = "META-INF/container.xml"
		private const val MIMETYPE_EPUB = "application/epub+zip"

		/** `<branch hash>_<chapter index>` — stable across resumed downloads, lexical == reading order. */
		private const val ENTRY_PATTERN = "%08d_%05d"

		private val GENERATED_ENTRIES = setOf(ENTRY_MIMETYPE, ENTRY_CONTAINER, ENTRY_NAME_INDEX)
	}
}
