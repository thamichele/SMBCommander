package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupTest {

    private fun media(path: String, size: Long = 3_000_000) = RemoteEntry("foto", path, false, size, 0)

    private fun kind(path: String, size: Long = 3_000_000) =
        CleanupRules.classify(listOf(media(path, size)), "Backup")[media(path, size)]?.kind

    @Test
    fun appImagesAreToBeChecked() {
        assertEquals(CleanupReason.Kind.Screenshot, kind("Backup/2026/10/Screenshot_20261029-101500_Chrome.jpg"))
        assertEquals(CleanupReason.Kind.ScreenRecording, kind("Backup/2026/10/Screen_Recording_20261029.mp4"))
        assertEquals(CleanupReason.Kind.Messaging, kind("Backup/2026/10/IMG-20261029-WA0003.jpg"))
        assertEquals(CleanupReason.Kind.Messaging, kind("Backup/Vecchie/Telegram Images/foto.jpg"))
        assertEquals(CleanupReason.Kind.Animated, kind("Backup/2026/10/sticker.webp", 40_000))
        assertEquals(CleanupReason.Kind.Tiny, kind("Backup/2026/10/icona.jpg", 20_000))
        assertEquals(CleanupReason.Kind.Downloaded, kind("Backup/Download/a8f5f167f44f4964e6c998dee827110c.jpg"))
        assertEquals(CleanupReason.Kind.Edited, kind("Backup/2026/10/IMG_20261029_101500-edited.jpg"))
        assertEquals(CleanupReason.Kind.ShortVideo, kind("Backup/2026/10/VID_20261029_101500.mp4", 900_000))
    }

    @Test
    fun cameraPhotosAreLeftAlone() {
        assertNull(kind("Backup/2026/10/PXL_20261029_101500123.jpg"))
        assertNull(kind("Backup/2026/10/IMG_20261029_101500.jpg"))
        assertNull(kind("Backup/2019/03/DSC01234.JPG"))
        // a folder of the backup named like an app does not count when it is the backup folder itself
        assertNull(CleanupRules.classify(listOf(media("Screenshots/2026/IMG_20261029_101500.jpg")), "Screenshots").values.firstOrNull())
    }

    @Test
    fun burstsAndCopiesAreToBeChecked() {
        val a = media("Backup/2026/10/IMG_20261029_101500.jpg")
        val b = media("Backup/2026/10/IMG_20261029_101501.jpg")
        val c = media("Backup/2026/10/IMG_20261029_101800.jpg")
        val copy = media("Backup/QuMagie/Camera/2026-10-29/IMG_20261029_101800.jpg")
        val found = CleanupRules.classify(listOf(a, b, c, copy), "Backup")
        assertEquals(CleanupReason.Kind.Burst, found[a]?.kind)
        assertEquals(CleanupReason.Kind.Burst, found[b]?.kind)
        assertNull(found[c])
        // copies have a tab of their own, side by side
        assertNull(found[copy])
    }

    @Test
    fun copiesAreFoundByNameWhateverTheEnding() {
        assertEquals("img_1234", CleanupRules.stem("IMG_1234 (2).jpg"))
        assertEquals("img_1234", CleanupRules.stem("IMG_1234_1.JPG"))
        assertEquals("img_1234", CleanupRules.stem("IMG_1234 - Copia (2).jpg"))
        assertEquals("img_1234", CleanupRules.stem("Copia di IMG_1234.jpg"))
        assertEquals("pxl_20261029_101500123", CleanupRules.stem("PXL_20261029_101500123.jpg"))
        assertEquals("img_20261029_101500", CleanupRules.stem("IMG_20261029_101500_1.jpg"))
        // a date is not a copy number
        assertEquals("mare 2026-10-29", CleanupRules.stem("Mare 2026-10-29.jpg"))
        val original = media("Backup/2026/10/IMG_1234.jpg")
        val heic = media("Backup/2026/10/IMG_1234.HEIC")
        val numbered = media("Backup/QuMagie/Camera/IMG_1234 (1).jpg")
        val other = media("Backup/2026/10/IMG_1235.jpg")
        val video = media("Backup/2026/10/IMG_1234.mp4")
        val short1 = media("Backup/foto_1.jpg")
        val short2 = media("Backup/foto_2.jpg")
        val groups = CleanupRules.duplicates(listOf(numbered, other, heic, original, video, short1, short2))
        assertEquals(1, groups.size)
        assertEquals(setOf(original, heic, numbered), groups[0].toSet())
        // shortest path first
        assertEquals(numbered, groups[0].last())
        // forty screenshots with a number are not forty copies
        val many = (1..10).map { media("Backup/Screenshot ($it).png") }
        assertTrue(CleanupRules.duplicates(many).isEmpty())
    }

    @Test
    fun keptPhotosAreRecognisedAfterAMove() {
        val kept = KeptPhotos()
        val photo = media("Backup/2026/10/IMG-20261029-WA0003.jpg", 1234)
        kept.add(photo, "Backup")
        assertTrue(kept.contains(photo, "Backup"))
        assertTrue(kept.contains(media("Backup/Archivio/IMG-20261029-WA0003.jpg", 1234), "Backup"))
        assertFalse(kept.contains(media("Backup/Archivio/IMG-20261029-WA0003.jpg", 999), "Backup"))
        kept.remove(photo, "Backup")
        assertFalse(kept.contains(photo, "Backup"))
    }

    @Test
    fun keptListLivesOnTheNasAndMerges() {
        val fs = FakeNasFileSystem()
        val one = KeptPhotos().apply { add(media("Backup/a.jpg", 1), "Backup") }
        KeptPhotos.write(fs, "foto", "Backup", one)
        val two = KeptPhotos().apply { add(media("Backup/b.jpg", 2), "Backup") }
        KeptPhotos.write(fs, "foto", "Backup", two)
        val read = KeptPhotos.read(fs, "foto", "Backup")
        assertTrue(read.contains(media("Backup/a.jpg", 1), "Backup"))
        assertTrue(read.contains(media("Backup/b.jpg", 2), "Backup"))
    }

    @Test
    fun trashKeepsThePlaceAndRestores() {
        val fs = FakeNasFileSystem()
        fs.put("foto", "Backup/2026/10/a.jpg", "a")
        val inTrash = CleanupTrash.discard(fs, fs.stat("foto", "Backup/2026/10/a.jpg")!!, "Backup")
        assertEquals("Backup/.smbcommander-cestino/2026/10/a.jpg", inTrash)
        assertNull(fs.stat("foto", "Backup/2026/10/a.jpg"))
        assertEquals(listOf(inTrash), CleanupTrash.contents(fs, "foto", "Backup").map { it.path })
        assertEquals("Backup/2026/10/a.jpg", CleanupTrash.originalOf(inTrash, "Backup"))
        CleanupTrash.restore(fs, "foto", inTrash, "Backup/2026/10/a.jpg")
        assertEquals("a", fs.read("foto", "Backup/2026/10/a.jpg"))
        CleanupTrash.discard(fs, fs.stat("foto", "Backup/2026/10/a.jpg")!!, "Backup")
        CleanupTrash.empty(fs, "foto", "Backup")
        assertTrue(fs.files.isEmpty())
    }

    @Test
    fun emptyingSkipsFilesGoneMeanwhile() {
        val fs = FakeNasFileSystem()
        for (name in listOf("a", "b", "c")) {
            fs.put("foto", "Backup/2026/10/$name.jpg", name)
            CleanupTrash.discard(fs, fs.stat("foto", "Backup/2026/10/$name.jpg")!!, "Backup")
        }
        fs.put("foto", "Backup/2026/10/d.jpg", "d")
        // b is deleted from another device while the bin is being emptied
        fs.beforeDelete = { if (it.endsWith("a.jpg")) fs.files.keys.removeAll { k -> k.endsWith("b.jpg") } }
        assertEquals(listOf("Backup/.smbcommander-cestino/2026/10/b.jpg"), CleanupTrash.empty(fs, "foto", "Backup"))
        assertEquals(setOf("foto/Backup/2026/10/d.jpg"), fs.files.keys)
    }

    @Test
    fun unlessGoneSkipsOnlyMissingFiles() {
        val fs = FakeNasFileSystem()
        fs.put("foto", "a.jpg", "a")
        fs.put("foto", "b.jpg", "b")
        assertNull(fs.unlessGone("foto", "x.jpg") { fs.delete("foto", "x.jpg") })
        assertEquals(Unit, fs.unlessGone("foto", "a.jpg") { fs.delete("foto", "a.jpg") })
        // the file is there and the failure is something else: that is not skipped
        assertThrows(NasException::class.java) { fs.unlessGone("foto", "b.jpg") { fs.rename("foto", "b.jpg", "b.jpg") } }
    }

    @Test
    fun deletedEntriesCanBeTakenBack() {
        val fs = FakeNasFileSystem()
        DeletedPhotos.add(fs, "foto", "Backup", listOf("1 aa", "2 bb"))
        DeletedPhotos.remove(fs, "foto", "Backup", listOf("1 aa"))
        assertEquals(setOf("2 bb"), DeletedPhotos.read(fs, "foto", "Backup"))
    }
}
