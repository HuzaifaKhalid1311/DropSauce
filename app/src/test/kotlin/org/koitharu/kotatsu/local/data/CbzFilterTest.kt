package org.koitharu.kotatsu.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CbzFilterTest {

    @Test
    fun `supported archive extensions include cbz cbr zip and pdf`() {
        assertTrue(isSupportedArchive("chapter.cbz"))
        assertTrue(isSupportedArchive("chapter.zip"))
        assertTrue(isSupportedArchive("chapter.pdf"))
        assertTrue(isSupportedArchive("CHAPTER.PDF"))
        assertTrue(isSupportedArchive("chapter.cbr"))
        assertTrue(isSupportedArchive("chapter.rar"))
    }

    @Test
    fun `unsupported archive extensions are rejected`() {
        assertFalse(isSupportedArchive("chapter.7z"))
        assertFalse(isSupportedArchive("chapter"))
        assertFalse(isSupportedArchive("chapter.jpg"))
    }
}

