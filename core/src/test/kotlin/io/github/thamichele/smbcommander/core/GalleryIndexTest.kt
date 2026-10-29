package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class GalleryIndexTest {

    private fun photo(path: String, size: Long = 1, modified: Long = 0) = RemoteEntry("foto", path, false, size, modified)

    @Test
    fun onlyChangedFoldersCountAsChanges() {
        val index = GalleryIndex(listOf(photo("2026/10/a.jpg"), photo("2026/09/b.jpg")))
        assertFalse(index.replace("2026/10", listOf(photo("2026/10/a.jpg"))))
        assertTrue(index.replace("2026/10", listOf(photo("2026/10/a.jpg"), photo("2026/10/c.jpg"))))
        assertEquals(3, index.all().size)
    }

    @Test
    fun foldersNotSeenAreDroppedUnlessTheirParentFailed() {
        val index = GalleryIndex(listOf(photo("2026/10/a.jpg"), photo("2025/01/b.jpg"), photo("Vecchie/x/c.jpg")))
        assertTrue(index.retain(seen = setOf("", "2026", "2026/10"), failed = setOf("Vecchie")))
        assertEquals(setOf("2026/10/a.jpg", "Vecchie/x/c.jpg"), index.all().map { it.path }.toSet())
    }

    @Test
    fun monthFromBackupFolders() {
        val t = GalleryDates.of(photo("Backup/2026/10/PXL_20261029_101500123.jpg"))
        assertEquals(2026 to 10, t.year to t.month)
        assertEquals(20261029101500L, t.sortKey)
    }

    @Test
    fun monthFromQuMagieStyleFolders() {
        assertEquals(2019 to 3, GalleryDates.of(photo("Camera Uploads/Telefono/2019-03-14/IMG_1234.JPG")).let { it.year to it.month })
        assertEquals(2018 to 7, GalleryDates.of(photo("Vecchie/2018_07 Mare/foto.jpg")).let { it.year to it.month })
        assertEquals(2017 to 2, GalleryDates.of(photo("2017/2/foto.jpg")).let { it.year to it.month })
        assertEquals(2016 to 12, GalleryDates.of(photo("Archivio/2016/12 Natale/foto.jpg")).let { it.year to it.month })
    }

    @Test
    fun monthFromFileNameWhenFoldersSayNothing() {
        assertEquals(2021 to 5, GalleryDates.of(photo("Varie/IMG_20210512_181500.jpg")).let { it.year to it.month })
        assertEquals(2020 to 8, GalleryDates.of(photo("Varie/2020-08-01 10.20.30.jpg")).let { it.year to it.month })
    }

    @Test
    fun monthFromFileDateAsLastResort() {
        val cal = Calendar.getInstance().apply { set(2015, Calendar.JUNE, 10, 12, 0, 0) }
        assertEquals(2015 to 6, GalleryDates.of(photo("Varie/DSC0001.jpg", modified = cal.timeInMillis)).let { it.year to it.month })
    }

    @Test
    fun numbersThatAreNotDatesAreIgnored() {
        val cal = Calendar.getInstance().apply { set(2015, Calendar.JUNE, 10, 12, 0, 0) }
        // "1234" is not a year, "2048x1536" not a date
        assertEquals(2015 to 6, GalleryDates.of(photo("1234/wallpaper_2048x1536.jpg", modified = cal.timeInMillis)).let { it.year to it.month })
    }
}
