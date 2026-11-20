/*
 * AlbumsTest.kt - tests of the albums of the gallery and of what the index knows about them
 *
 * Version: 1.20.0
 */
package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumsTest {

    private fun photo(path: String, modified: Long = 0) = RemoteEntry("foto", path, false, 1, modified)

    private fun albums(index: GalleryIndex, root: String = "Backup") =
        Albums.list(root, index.folders(), index.emptyFolders()) { it.modified }

    @Test
    fun everyFolderWithPhotosIsAnAlbumNamedAfterItsPath() {
        val index = GalleryIndex(listOf(photo("Backup/Vacanze/a.jpg"), photo("Backup/Vacanze/Mare/b.jpg"), photo("Backup/c.jpg")))
        assertEquals(listOf("", "Vacanze", "Vacanze/Mare"), albums(index).map { it.name })
        assertEquals(listOf("Backup", "Backup/Vacanze", "Backup/Vacanze/Mare"), albums(index).map { it.path })
    }

    @Test
    fun foldersThatOnlyHoldFoldersAreNotAlbums() {
        val index = GalleryIndex(listOf(photo("Backup/2026/10/a.jpg")))
        assertEquals(listOf("2026/10"), albums(index).map { it.name })
    }

    @Test
    fun namedAlbumsComeBeforeMonthsAndMonthsNewestFirst() {
        val index = GalleryIndex(
            listOf(photo("Backup/2025/12/a.jpg"), photo("Backup/zoo/b.jpg"), photo("Backup/2026/01/c.jpg"), photo("Backup/Amici/d.jpg")),
        )
        assertEquals(listOf("Amici", "zoo", "2026/01", "2025/12"), albums(index).map { it.name })
        assertEquals(listOf(false, false, true, true), albums(index).map { it.isDated })
    }

    @Test
    fun coverIsTheNewestPhotoAndCountOnlyTheFolderItself() {
        val index = GalleryIndex(
            listOf(photo("Backup/Gita/old.jpg", 1), photo("Backup/Gita/new.jpg", 9), photo("Backup/Gita/Sera/x.jpg", 99)),
        )
        val gita = albums(index).first { it.name == "Gita" }
        assertEquals(2, gita.count)
        assertEquals("Backup/Gita/new.jpg", gita.cover?.path)
    }

    @Test
    fun aNewAlbumIsListedWhileStillEmpty() {
        val index = GalleryIndex(listOf(photo("Backup/2026/10/a.jpg")))
        index.addEmpty(Albums.newPath("Backup", " Vacanze "))
        val album = albums(index).first()
        assertEquals("Vacanze", album.name)
        assertEquals(0, album.count)
        assertNull(album.cover)
    }

    @Test
    fun emptyAlbumsSurviveARestartOfTheApp() {
        val index = GalleryIndex(listOf(photo("Backup/2026/10/a.jpg")))
        index.addEmpty("Backup/Vacanze")
        val again = GalleryIndex(index.stored("foto"))
        assertEquals(listOf("Vacanze", "2026/10"), albums(again).map { it.name })
        assertEquals(1, again.all().size)
    }

    @Test
    fun aWalkKeepsEmptyLeafFoldersAndDropsThoseGone() {
        val index = GalleryIndex()
        assertTrue(index.replace("Backup/Vuoto", emptyList(), isEmptyAlbum = true))
        assertFalse(index.replace("Backup/Vuoto", emptyList(), isEmptyAlbum = true))
        assertEquals(setOf("Backup/Vuoto"), index.emptyFolders())
        assertTrue(index.retain(seen = setOf("Backup"), failed = emptySet()))
        assertTrue(index.emptyFolders().isEmpty())
    }

    @Test
    fun aFolderThatGetsPhotosIsNoLongerEmpty() {
        val index = GalleryIndex()
        index.addEmpty("Backup/Vacanze")
        assertTrue(index.replace("Backup/Vacanze", listOf(photo("Backup/Vacanze/a.jpg"))))
        assertTrue(index.emptyFolders().isEmpty())
    }

    @Test
    fun movingAPhotoChangesItsAlbumAndCanBeRepeated() {
        val a = photo("Backup/2026/10/a.jpg")
        val index = GalleryIndex(listOf(a, photo("Backup/2026/10/b.jpg")))
        index.addEmpty("Backup/Vacanze")
        repeat(2) { index.move(a, "Backup/Vacanze/a.jpg") }
        assertEquals(mapOf("2026/10" to 1, "Vacanze" to 1), albums(index).associate { it.name to it.count })
        assertEquals(2, index.all().size)
    }

    @Test
    fun anAlbumEmptiedByAMoveStaysAsAnEmptyAlbum() {
        val a = photo("Backup/Gita/a.jpg")
        val index = GalleryIndex(listOf(a, photo("Backup/Altro/b.jpg")))
        index.move(a, "Backup/Altro/a.jpg")
        assertEquals(mapOf("Altro" to 2, "Gita" to 0), albums(index).associate { it.name to it.count })
    }

    @Test
    fun newAlbumRejectsNamesThatAreNotFoldersOrWouldBeHidden() {
        assertEquals("Backup/Vacanze 2026", Albums.newPath("Backup", "Vacanze 2026"))
        assertEquals("Vacanze", Albums.newPath("", "Vacanze"))
        for (bad in listOf("", "  ", "a/b", "..", ".nascosto", "@Recycle", "#recycle", "~tmp", "punto.")) {
            assertThrows(bad, IllegalArgumentException::class.java) { Albums.newPath("Backup", bad) }
        }
    }

    @Test
    fun holdsOnlyThePhotosDirectlyInTheAlbum() {
        assertTrue(Albums.holds("Backup/Gita", "Backup/Gita/a.jpg"))
        assertFalse(Albums.holds("Backup/Gita", "Backup/Gita/Sera/a.jpg"))
        assertFalse(Albums.holds("Backup/Gita", "Backup/Gita 2/a.jpg"))
        assertTrue(Albums.holds("", "a.jpg"))
    }

    @Test
    fun movingToAnAlbumRenamesWithoutOverwriting() {
        val fs = FakeNasFileSystem()
        fs.put("foto", "Backup/2026/10/a.jpg", "nuova")
        fs.put("foto", "Backup/Vacanze/a.jpg", "già lì")
        val source = fs.stat("foto", "Backup/2026/10/a.jpg")!!
        val target = Transfers.move(fs, source, "foto", "Backup/Vacanze")
        assertEquals("Backup/Vacanze/a (2).jpg", target)
        assertNull(fs.stat("foto", "Backup/2026/10/a.jpg"))
        assertEquals("già lì", fs.read("foto", "Backup/Vacanze/a.jpg"))
        assertEquals("nuova", fs.read("foto", target))
    }
}
