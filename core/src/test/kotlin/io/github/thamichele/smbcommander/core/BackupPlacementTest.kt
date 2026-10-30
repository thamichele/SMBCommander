package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPlacementTest {

    private val fs = FakeNasFileSystem()

    private fun find(content: String, target: String = "Foto/2026/10/IMG_1.jpg"): BackupPlacement {
        var hashed = 0
        val result = BackupPlacement.find(fs, "home", target, content.length.toLong()) {
            hashed++
            Integrity.sha256(content.byteInputStream())
        }
        assertTrue(hashed <= 1)
        return result
    }

    @Test
    fun freeNameIsUsedAsIs() {
        assertEquals(BackupPlacement.Upload("Foto/2026/10/IMG_1.jpg"), find("photo"))
    }

    @Test
    fun samePhotoAlreadyOnNasIsSkipped() {
        fs.put("home", "Foto/2026/10/IMG_1.jpg", "photo")
        assertEquals(BackupPlacement.AlreadyThere("Foto/2026/10/IMG_1.jpg"), find("photo"))
    }

    @Test
    fun sameNameSameSizeDifferentContentGetsSuffix() {
        fs.put("home", "Foto/2026/10/IMG_1.jpg", "other")
        assertEquals(BackupPlacement.Upload("Foto/2026/10/IMG_1_2.jpg"), find("photo"))
    }

    @Test
    fun sameNameDifferentSizeGetsSuffix() {
        fs.put("home", "Foto/2026/10/IMG_1.jpg", "a longer photo")
        assertEquals(BackupPlacement.Upload("Foto/2026/10/IMG_1_2.jpg"), find("photo"))
    }

    @Test
    fun photoAlreadySavedWithSuffixIsFoundAfterReinstall() {
        fs.put("home", "Foto/2026/10/IMG_1.jpg", "other")
        fs.put("home", "Foto/2026/10/IMG_1_2.jpg", "photo")
        assertEquals(BackupPlacement.AlreadyThere("Foto/2026/10/IMG_1_2.jpg"), find("photo"))
    }

    @Test
    fun nextFreeSuffix() {
        fs.put("home", "Foto/IMG_1.jpg", "other")
        fs.put("home", "Foto/IMG_1_2.jpg", "third")
        assertEquals(BackupPlacement.Upload("Foto/IMG_1_3.jpg"), find("photo", "Foto/IMG_1.jpg"))
    }

    @Test
    fun numberedKeepsExtension() {
        assertEquals("IMG_1_2.jpg", RemotePath.numbered("IMG_1.jpg", 2))
        assertEquals("README_3", RemotePath.numbered("README", 3))
        assertEquals(".hidden_2", RemotePath.numbered(".hidden", 2))
    }
}
