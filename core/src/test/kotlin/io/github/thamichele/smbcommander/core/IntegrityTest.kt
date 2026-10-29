package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class IntegrityTest {

    private val fs = FakeNasFileSystem()

    @Test
    fun uploadDamagedOnceIsSentAgain() {
        fs.corruptWrites = 1
        EditSync.safeUpload(fs, "home", "a.txt", 5, { ByteArrayInputStream("hello".toByteArray()) })
        assertEquals("hello", fs.read("home", "a.txt"))
        assertEquals(setOf("home/a.txt"), fs.files.keys)
    }

    @Test
    fun uploadDamagedTwiceFailsAndKeepsTheOriginal() {
        fs.put("home", "a.txt", "old")
        fs.corruptWrites = 2
        try {
            EditSync.safeUpload(fs, "home", "a.txt", 5, { ByteArrayInputStream("hello".toByteArray()) })
            fail()
        } catch (e: NasException) {
            assertTrue(e.message!!.contains("Verifica"))
        }
        assertEquals("old", fs.read("home", "a.txt"))
        assertEquals(setOf("home/a.txt"), fs.files.keys)
    }

    @Test
    fun uploadShorterThanAnnouncedFails() {
        try {
            EditSync.safeUpload(fs, "home", "a.txt", 10, { ByteArrayInputStream("hello".toByteArray()) })
            fail()
        } catch (e: NasException) {
        }
        assertTrue(fs.files.isEmpty())
    }

    @Test
    fun damagedCopyIsDroppedAndMoveKeepsTheOriginal() {
        fs.put("home", "Doc/a.txt", "hello")
        fs.corruptWrites = 1
        try {
            Transfers.move(fs, fs.stat("home", "Doc/a.txt")!!, "backup", "Doc")
            fail()
        } catch (e: NasException) {
        }
        assertEquals("hello", fs.read("home", "Doc/a.txt"))
        assertEquals(setOf("home/Doc/a.txt"), fs.files.keys)
    }

    @Test
    fun folderMoveBetweenSharesDeletesNothingWhenOneFileFails() {
        fs.put("home", "Foto/a.jpg", "a")
        fs.put("home", "Foto/b.jpg", "b")
        fs.corruptWrites = 0
        val source = fs.stat("home", "Foto")!!
        // the second file arrives damaged
        val failing = object : NasFileSystem by fs {
            var copies = 0
            override fun copyFile(fromShare: String, from: String, toShare: String, to: String, progress: Progress): String? {
                if (++copies == 2) fs.corruptWrites = 1
                return fs.copyFile(fromShare, from, toShare, to, progress)
            }
        }
        try {
            Transfers.move(failing, source, "backup", "")
            fail()
        } catch (e: NasException) {
        }
        assertEquals("a", fs.read("home", "Foto/a.jpg"))
        assertEquals("b", fs.read("home", "Foto/b.jpg"))
    }

    @Test
    fun downloadReturnsTheHashOfTheBytes() {
        fs.put("home", "a.txt", "hello")
        val out = ByteArrayOutputStream()
        val hash = Integrity.download(fs, "home", "a.txt", out)
        assertEquals("hello", out.toString())
        assertEquals(Integrity.sha256(ByteArrayInputStream("hello".toByteArray())), hash)
    }
}
