package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileTrashTest {

    private val fs = FakeNasFileSystem()

    @Test
    fun aFileGoesToTheBinAndComesBack() {
        fs.put("home", "Doc/a.txt", "hello")
        val item = FileTrash.discard(fs, fs.stat("home", "Doc/a.txt")!!, now = 1000)
        assertNull(fs.read("home", "Doc/a.txt"))
        val listed = FileTrash.contents(fs, "home").single()
        assertEquals("Doc/a.txt", listed.original)
        assertEquals(1000L, listed.deletedAt)
        assertEquals(item.entry.path, listed.entry.path)
        assertEquals("Doc/a.txt", FileTrash.restore(fs, listed))
        assertEquals("hello", fs.read("home", "Doc/a.txt"))
        assertTrue(FileTrash.contents(fs, "home").isEmpty())
    }

    @Test
    fun aFolderKeepsItsContent() {
        fs.put("home", "Foto/2026/a.jpg", "a")
        FileTrash.discard(fs, fs.stat("home", "Foto")!!, now = 1)
        val item = FileTrash.contents(fs, "home").single()
        assertTrue(item.entry.isDirectory)
        FileTrash.restore(fs, item)
        assertEquals("a", fs.read("home", "Foto/2026/a.jpg"))
    }

    @Test
    fun twoDeletionsWithTheSameNameStaySeparate() {
        fs.put("home", "A/x.txt", "1")
        fs.put("home", "B/x.txt", "2")
        FileTrash.discard(fs, fs.stat("home", "A/x.txt")!!, now = 5)
        FileTrash.discard(fs, fs.stat("home", "B/x.txt")!!, now = 5)
        assertEquals(setOf("A/x.txt", "B/x.txt"), FileTrash.contents(fs, "home").map { it.original }.toSet())
    }

    @Test
    fun restoringOverATakenNameKeepsBoth() {
        fs.put("home", "a.txt", "old")
        FileTrash.discard(fs, fs.stat("home", "a.txt")!!, now = 1)
        fs.put("home", "a.txt", "new")
        assertEquals("a (2).txt", FileTrash.restore(fs, FileTrash.contents(fs, "home").single()))
        assertEquals("new", fs.read("home", "a.txt"))
    }

    @Test
    fun oldDeletionsArePurged() {
        fs.put("home", "a.txt", "a")
        fs.put("home", "b.txt", "b")
        val day = 24L * 60 * 60 * 1000
        FileTrash.discard(fs, fs.stat("home", "a.txt")!!, now = 1 * day)
        FileTrash.discard(fs, fs.stat("home", "b.txt")!!, now = 40 * day)
        assertEquals(1, FileTrash.purge(fs, "home", now = 41 * day))
        assertEquals(listOf("b.txt"), FileTrash.contents(fs, "home").map { it.original })
    }

    @Test
    fun measureAndSearchWalkAllLevels() {
        fs.put("home", "Foto/2026/vacanza.jpg", "12345")
        fs.put("home", "Foto/2025/Vacanza mare/b.jpg", "123")
        fs.put("home", "Foto/.nascosta/vacanza.txt", "1")
        val totals = FolderScan.measure(fs, "home", "Foto")
        assertEquals(3, totals.files)
        assertEquals(9L, totals.bytes)
        val found = ArrayList<String>()
        FolderScan.search(fs, "home", "Foto", "VACANZA") { found += it.path }
        assertEquals(setOf("Foto/2026/vacanza.jpg", "Foto/2025/Vacanza mare"), found.toSet())
    }
}
