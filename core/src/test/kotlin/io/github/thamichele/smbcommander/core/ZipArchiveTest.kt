package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipArchiveTest {

    private val fs = FakeNasFileSystem()

    private fun zip(vararg files: Pair<String, String>, stored: Boolean = false): String {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in files) {
                val bytes = content.toByteArray()
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = bytes.size.toLong()
                    e.crc = java.util.zip.CRC32().apply { update(bytes) }.value
                }
                z.putNextEntry(e)
                z.write(bytes)
                z.closeEntry()
            }
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun put(path: String, latin1: String) {
        fs.files["home/$path"] = latin1.toByteArray(Charsets.ISO_8859_1)
        fs.modified["home/$path"] = 1
    }

    @Test
    fun listsAndReadsDeflatedEntries() {
        val big = "ciao ".repeat(10_000)
        put("a.zip", zip("Documenti/lettera.txt" to "cara Anna", "Documenti/Foto/x.jpg" to big, "note.txt" to "ok"))
        fs.openRandom("home", "a.zip").use { reader ->
            val archive = ZipArchive.read(reader)
            assertEquals(listOf("Documenti", "note.txt"), archive.list("").map { it.name })
            assertEquals(listOf("lettera.txt", "Foto"), archive.list("Documenti").map { it.name })
            val entry = archive.entries.first { it.path == "Documenti/Foto/x.jpg" }
            assertEquals(big, archive.open(entry).readBytes().decodeToString())
        }
    }

    @Test
    fun readsStoredEntries() {
        put("s.zip", zip("a.txt" to "hello", stored = true))
        fs.openRandom("home", "s.zip").use { reader ->
            val archive = ZipArchive.read(reader)
            assertEquals("hello", archive.open(archive.entries.single()).readBytes().decodeToString())
        }
    }

    @Test
    fun extractsAFolderOfTheArchive() {
        put("a.zip", zip("Documenti/lettera.txt" to "cara Anna", "Documenti/Foto/x.jpg" to "foto", "note.txt" to "ok"))
        fs.openRandom("home", "a.zip").use { reader ->
            val archive = ZipArchive.read(reader)
            ZipExtract.extract(fs, archive, archive.filesUnder("Documenti"), "", "home", "Estratti", Conflict.KeepBoth, TransferLog())
        }
        assertEquals("cara Anna", fs.read("home", "Estratti/Documenti/lettera.txt"))
        assertEquals("foto", fs.read("home", "Estratti/Documenti/Foto/x.jpg"))
        assertEquals(null, fs.read("home", "Estratti/note.txt"))
    }

    @Test
    fun namesClimbingOutAreLeftOut() {
        put("bad.zip", zip("../fuori.txt" to "x", "dentro.txt" to "y"))
        fs.openRandom("home", "bad.zip").use { reader ->
            assertEquals(listOf("dentro.txt"), ZipArchive.read(reader).entries.map { it.path })
        }
    }
}
