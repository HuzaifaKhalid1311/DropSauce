package org.koitharu.kotatsu.local.data.input

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PdfOutlineTest {

	@Test
	fun `top level contents give titles and page indexes`() {
		val file = File.createTempFile("outline", ".pdf")
		try {
			file.writeBytes(
				pdf(
					"<< /Type /Catalog /Pages 2 0 R /Outlines 6 0 R >>",
					"<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>",
					"<< /Type /Page /Parent 2 0 R >>",
					"<< /Type /Page /Parent 2 0 R >>",
					"<< /Type /Page /Parent 2 0 R >>",
					"<< /Type /Outlines /First 7 0 R /Last 8 0 R >>",
					"<< /Title (Intro \\(part 1\\)) /Parent 6 0 R /Next 8 0 R /Dest [3 0 R /Fit] >>",
					// UTF-16 title and a GoTo action, the other common shape
					"<< /Title <FEFF0043006800610070007400650072002000540077006F> /Parent 6 0 R /A << /S /GoTo /D [5 0 R /Fit] >> >>",
				),
			)
			assertEquals(
				listOf("Intro (part 1)" to 0, "Chapter Two" to 2),
				PdfOutline.read(file).map { it.title to it.pageIndex },
			)
		} finally {
			file.delete()
		}
	}

	@Test
	fun `a file without contents gives nothing`() {
		val file = File.createTempFile("outline", ".pdf")
		try {
			file.writeBytes(pdf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [] /Count 0 >>"))
			assertEquals(emptyList<Pair<String, Int>>(), PdfOutline.read(file).map { it.title to it.pageIndex })
		} finally {
			file.delete()
		}
	}

	/** A PDF with a classic cross-reference table holding [objects] as objects 1, 2, ... */
	private fun pdf(vararg objects: String): ByteArray {
		val out = StringBuilder("%PDF-1.4\n")
		val offsets = objects.mapIndexed { i, body ->
			out.length.also { out.append("${i + 1} 0 obj\n$body\nendobj\n") }
		}
		val xref = out.length
		out.append("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
		offsets.forEach { out.append("%010d 00000 n \n".format(it)) }
		out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
		return out.toString().toByteArray(Charsets.ISO_8859_1)
	}
}
