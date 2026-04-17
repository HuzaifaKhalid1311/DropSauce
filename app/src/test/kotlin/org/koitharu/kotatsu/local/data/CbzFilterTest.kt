package org.koitharu.kotatsu.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CbzFilterTest {

    @Test
    fun `supported archive extensions include cbz zip and pdf`() {
        assertTrue(isSupportedArchive("chapter.cbz"))
        assertTrue(isSupportedArchive("chapter.zip"))
        assertTrue(isSupportedArchive("chapter.pdf"))
        assertTrue(isSupportedArchive("CHAPTER.PDF"))
    }

    @Test
    fun `unsupported archive extensions are rejected`() {
        assertFalse(isSupportedArchive("chapter.rar"))
        assertFalse(isSupportedArchive("chapter"))
        assertFalse(isSupportedArchive("chapter.jpg"))
    }
}

