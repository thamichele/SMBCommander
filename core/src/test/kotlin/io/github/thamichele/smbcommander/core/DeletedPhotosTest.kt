package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletedPhotosTest {

    private val fs = FakeNasFileSystem()

    @Test
    fun emptyWhenNothingWasDeleted() {
        assertTrue(DeletedPhotos.read(fs, "home", "Foto").isEmpty())
    }

    @Test
    fun entriesAreKeptAcrossAdds() {
        DeletedPhotos.add(fs, "home", "Foto", listOf(DeletedPhotos.entry(5, "aa")))
        DeletedPhotos.add(fs, "home", "Foto", listOf(DeletedPhotos.entry(7, "bb"), DeletedPhotos.entry(5, "aa")))
        assertEquals(setOf("5 aa", "7 bb"), DeletedPhotos.read(fs, "home", "Foto"))
        assertEquals(setOf(5L, 7L), DeletedPhotos.sizes(DeletedPhotos.read(fs, "home", "Foto")))
    }

    @Test
    fun listIsHiddenInTheFolder() {
        DeletedPhotos.add(fs, "home", "Foto", listOf(DeletedPhotos.entry(5, "aa")))
        assertTrue(fs.read("home", "Foto/.smbcommander-eliminate.txt")!!.startsWith("5 aa"))
    }
}
