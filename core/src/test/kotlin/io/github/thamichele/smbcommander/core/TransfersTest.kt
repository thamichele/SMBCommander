package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class TransfersTest {

    private val fs = FakeNasFileSystem()

    private fun entry(share: String, path: String) = fs.stat(share, path)!!

    @Test
    fun copiesAFileAndKeepsTheOriginal() {
        fs.put("home", "Doc/a.txt", "hello")
        assertEquals("Archivio/a.txt", Transfers.copy(fs, entry("home", "Doc/a.txt"), "home", "Archivio"))
        assertEquals("hello", fs.read("home", "Archivio/a.txt"))
        assertEquals("hello", fs.read("home", "Doc/a.txt"))
        assertEquals(setOf("home/Doc/a.txt", "home/Archivio/a.txt"), fs.files.keys)
    }

    @Test
    fun takenNameGetsANumber() {
        fs.put("home", "Doc/a.txt", "new")
        fs.put("home", "Archivio/a.txt", "old")
        assertEquals("Archivio/a (2).txt", Transfers.copy(fs, entry("home", "Doc/a.txt"), "home", "Archivio"))
        assertEquals("old", fs.read("home", "Archivio/a.txt"))
    }

    @Test
    fun copiesAFolderWithItsContent() {
        fs.put("home", "Foto/2026/a.jpg", "a")
        fs.put("home", "Foto/b.jpg", "b")
        Transfers.copy(fs, entry("home", "Foto"), "backup", "")
        assertEquals("a", fs.read("backup", "Foto/2026/a.jpg"))
        assertEquals("b", fs.read("backup", "Foto/b.jpg"))
    }

    @Test
    fun moveInsideAShareIsARename() {
        fs.put("home", "Doc/a.txt", "hello")
        assertEquals("Archivio/a.txt", Transfers.move(fs, entry("home", "Doc/a.txt"), "home", "Archivio"))
        assertNull(fs.read("home", "Doc/a.txt"))
        assertEquals("hello", fs.read("home", "Archivio/a.txt"))
    }

    @Test
    fun moveBetweenSharesCopiesThenDeletes() {
        fs.put("home", "Foto/a.jpg", "a")
        Transfers.move(fs, entry("home", "Foto"), "backup", "Vecchie")
        assertEquals("a", fs.read("backup", "Vecchie/Foto/a.jpg"))
        assertNull(fs.read("home", "Foto/a.jpg"))
    }

    @Test
    fun moveToTheSameFolderDoesNothing() {
        fs.put("home", "Doc/a.txt", "hello")
        assertEquals("Doc/a.txt", Transfers.move(fs, entry("home", "Doc/a.txt"), "home", "Doc"))
        assertEquals(setOf("home/Doc/a.txt"), fs.files.keys)
    }

    @Test
    fun aFolderCannotGoInsideItself() {
        fs.put("home", "Foto/2026/a.jpg", "a")
        try {
            Transfers.move(fs, entry("home", "Foto"), "home", "Foto/2026")
            fail()
        } catch (e: NasException) {
            assertEquals("a", fs.read("home", "Foto/2026/a.jpg"))
        }
    }

    @Test
    fun randomReadsReachAnyPosition() {
        fs.put("home", "v.mp4", "0123456789")
        fs.openRandom("home", "v.mp4").use { r ->
            val buf = ByteArray(4)
            assertEquals(4, r.read(6, buf, 0, 4))
            assertEquals("6789", buf.decodeToString())
            assertEquals(-1, r.read(10, buf, 0, 4))
        }
    }
}
