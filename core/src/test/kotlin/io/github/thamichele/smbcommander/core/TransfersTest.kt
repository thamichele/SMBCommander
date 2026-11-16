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
    fun replaceOverwritesAFile() {
        fs.put("home", "Doc/a.txt", "new")
        fs.put("home", "Archivio/a.txt", "old")
        assertEquals("Archivio/a.txt", Transfers.copy(fs, entry("home", "Doc/a.txt"), "home", "Archivio", conflict = Conflict.Replace))
        assertEquals("new", fs.read("home", "Archivio/a.txt"))
    }

    @Test
    fun skipLeavesTheFileAlone() {
        fs.put("home", "Doc/a.txt", "new")
        fs.put("home", "Archivio/a.txt", "old")
        val log = TransferLog()
        Transfers.copy(fs, entry("home", "Doc/a.txt"), "home", "Archivio", conflict = Conflict.Skip, log = log)
        assertEquals("old", fs.read("home", "Archivio/a.txt"))
        assertEquals(1, log.skipped)
    }

    @Test
    fun replaceNeverOverwritesItself() {
        fs.put("home", "Doc/a.txt", "a")
        assertEquals("Doc/a (2).txt", Transfers.copy(fs, entry("home", "Doc/a.txt"), "home", "Doc", conflict = Conflict.Replace))
        assertEquals("a", fs.read("home", "Doc/a.txt"))
    }

    @Test
    fun foldersAreMergedWhenSkipping() {
        fs.put("home", "Foto/a.jpg", "new a")
        fs.put("home", "Foto/b.jpg", "b")
        fs.put("backup", "Foto/a.jpg", "old a")
        Transfers.copy(fs, entry("home", "Foto"), "backup", "", conflict = Conflict.Skip)
        assertEquals("old a", fs.read("backup", "Foto/a.jpg"))
        assertEquals("b", fs.read("backup", "Foto/b.jpg"))
    }

    @Test
    fun foldersAreMergedWhenReplacing() {
        fs.put("home", "Foto/a.jpg", "new a")
        fs.put("backup", "Foto/a.jpg", "old a")
        fs.put("backup", "Foto/c.jpg", "c")
        Transfers.copy(fs, entry("home", "Foto"), "backup", "", conflict = Conflict.Replace)
        assertEquals("new a", fs.read("backup", "Foto/a.jpg"))
        assertEquals("c", fs.read("backup", "Foto/c.jpg"))
    }

    @Test
    fun aMoveBetweenSharesKeepsTheSkippedOriginals() {
        fs.put("home", "Foto/a.jpg", "new a")
        fs.put("home", "Foto/b.jpg", "b")
        fs.put("backup", "Foto/a.jpg", "old a")
        Transfers.move(fs, entry("home", "Foto"), "backup", "", conflict = Conflict.Skip)
        assertEquals("new a", fs.read("home", "Foto/a.jpg"))
        assertNull(fs.read("home", "Foto/b.jpg"))
        assertEquals("b", fs.read("backup", "Foto/b.jpg"))
        assertEquals("old a", fs.read("backup", "Foto/a.jpg"))
    }

    @Test
    fun aMoveInsideAShareMergesFolders() {
        fs.put("home", "Nuove/2026/a.jpg", "a")
        fs.put("home", "Nuove/2026/b.jpg", "new b")
        fs.put("home", "Foto/2026/b.jpg", "old b")
        Transfers.move(fs, entry("home", "Nuove/2026"), "home", "Foto", conflict = Conflict.Replace)
        assertEquals("a", fs.read("home", "Foto/2026/a.jpg"))
        assertEquals("new b", fs.read("home", "Foto/2026/b.jpg"))
        assertNull(fs.stat("home", "Nuove/2026"))
    }

    @Test
    fun aCopyRunAgainCarriesOnWithTheSameName() {
        fs.put("home", "Foto/a.jpg", "a")
        fs.put("home", "Foto/b.jpg", "b")
        fs.put("backup", "Foto/x.jpg", "x")
        val log = TransferLog()
        // the first run stops after the first file, as a dropped connection would
        var calls = 0
        val failing = Transfers.Listener { name, _, _ -> if (name.startsWith("Verifica") && ++calls == 2) throw NasException("connection lost") }
        try {
            Transfers.copy(fs, entry("home", "Foto"), "backup", "", failing, log = log)
            fail()
        } catch (e: NasException) {
            assertEquals(1, log.written)
        }
        assertEquals("Foto (2)", Transfers.copy(fs, entry("home", "Foto"), "backup", "", log = log))
        assertEquals("a", fs.read("backup", "Foto (2)/a.jpg"))
        assertEquals("b", fs.read("backup", "Foto (2)/b.jpg"))
        assertEquals(setOf("backup/Foto/x.jpg", "backup/Foto (2)/a.jpg", "backup/Foto (2)/b.jpg"), fs.files.keys.filter { it.startsWith("backup") }.toSet())
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
