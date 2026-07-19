package com.samdvich.familyarchivegallery

import com.samdvich.familyarchivegallery.data.scanner.ArchiveScanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveScannerTest {
    @Test
    fun supportedExtensionsAreCaseInsensitive() {
        assertTrue(ArchiveScanner.isSupportedImage("holiday.JPG"))
        assertTrue(ArchiveScanner.isSupportedImage("portrait.heic"))
        assertTrue(ArchiveScanner.isSupportedImage("scan.webp"))
    }

    @Test
    fun unsupportedAndTemporaryFilesAreRejected() {
        assertFalse(ArchiveScanner.isSupportedImage("notes.txt"))
        assertFalse(ArchiveScanner.isSupportedImage("image.jpg.tmp"))
        assertFalse(ArchiveScanner.isSupportedImage("no-extension"))
    }
}
