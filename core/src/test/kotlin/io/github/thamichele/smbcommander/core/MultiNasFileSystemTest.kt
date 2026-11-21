package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MultiNasFileSystemTest {

    private val main = FakeNasFileSystem()
    private val second = FakeNasFileSystem()
    private val fs = MultiNasFileSystem(main = { main }, other = { id -> if (id == "2") second else null })
    private val music = NasShares.of("2", "Musica")

    @Test
    fun namesOfTheMainNasStayAsTheyAre() {
        assertNull(NasShares.nasOf("Multimedia"))
        assertEquals("Multimedia", NasShares.localOf("Multimedia"))
        assertFalse(NasShares.isRoot("Multimedia"))
        assertEquals("Backup$", NasShares.display("Backup$"))
    }

    @Test
    fun namesOfAnotherNasAreSplitAndShown() {
        NasShares.names = mapOf("2" to "Ufficio")
        assertEquals("nas2:Musica", music)
        assertEquals("2", NasShares.nasOf(music))
        assertEquals("Musica", NasShares.localOf(music))
        assertTrue(NasShares.isRoot(NasShares.root("2")))
        assertEquals("Ufficio / Musica / Rock", NasShares.display(music, "Rock"))
        assertEquals("Ufficio", NasShares.shownName(NasShares.root("2")))
        assertEquals("Musica", RemoteEntry(music, "", true, 0, 0).name)
    }

    @Test
    fun callsReachTheRightNas() {
        main.put("Multimedia", "a.txt", "main")
        second.put("Musica", "a.txt", "second")
        assertEquals(listOf("Multimedia"), fs.list("Multimedia", "").map { it.share }.distinct())
        val listed = fs.list(music, "")
        assertEquals(listOf(music), listed.map { it.share })
        assertEquals("second", fs.openRead(music, "a.txt").readBytes().decodeToString())
    }

    @Test
    fun theRootListsTheSharesOfThatNas() {
        second.put("Musica", "a.txt", "x")
        second.put("Video", "b.mp4", "y")
        val shares = fs.list(NasShares.root("2"), "")
        assertEquals(listOf(music, NasShares.of("2", "Video")), shares.map { it.share })
        assertTrue(shares.all { it.isDirectory && it.path.isEmpty() })
        assertTrue(fs.stat(NasShares.root("2"), "")!!.isDirectory)
        try {
            fs.createDirectory(NasShares.root("2"), "nuova")
            fail("the root of a NAS is not a folder on a disk")
        } catch (_: NasException) {
        }
    }

    @Test
    fun copiesAndMovesBetweenTwoNas() {
        main.put("Multimedia", "Foto/a.jpg", "photo")
        val entry = fs.stat("Multimedia", "Foto/a.jpg")!!
        assertEquals("a.jpg", Transfers.copy(fs, entry, music, ""))
        assertEquals("photo", second.read("Musica", "a.jpg"))
        assertEquals("photo", main.read("Multimedia", "Foto/a.jpg"))

        Transfers.move(fs, fs.stat(music, "a.jpg")!!, "Multimedia", "Altro")
        assertEquals("photo", main.read("Multimedia", "Altro/a.jpg"))
        assertNull(second.read("Musica", "a.jpg"))
    }

    @Test
    fun aRemovedNasGivesAClearError() {
        try {
            fs.list(NasShares.of("9", "Musica"), "")
            fail("NAS 9 is not configured")
        } catch (e: NasException) {
            assertTrue(e.message!!.contains("NAS 9"))
        }
    }
}
