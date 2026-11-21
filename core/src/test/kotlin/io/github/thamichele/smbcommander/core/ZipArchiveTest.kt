package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
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

    /* Offset of the first central directory record ("PK\u0001\u0002") in an archive made by [zip]. */
    private fun centralRecord(latin1: String) = latin1.indexOf("PK\u0001\u0002")

    /* Writes the 32-bit little-endian [value] at [at] of the archive. */
    private fun patch32(latin1: String, at: Int, value: Long): String {
        val bytes = latin1.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until 4) bytes[at + i] = (value shr (8 * i)).toByte()
        return String(bytes, Charsets.ISO_8859_1)
    }

    @Test
    fun entryInflatingPastItsSizeIsStopped() {
        val original = zip("bomba.txt" to "a".repeat(200_000))
        // the index now says 10 bytes: inflating must stop there instead of going on to the real 200 000
        put("bomb.zip", patch32(original, centralRecord(original) + 24, 10))
        fs.openRandom("home", "bomb.zip").use { reader ->
            val archive = ZipArchive.read(reader)
            assertThrows(IOException::class.java) { archive.open(archive.entries.single()).readBytes() }
        }
    }

    @Test
    fun indexWithLengthsPastItsEndIsRefused() {
        val original = zip("a.txt" to "x")
        // name length of the record set far beyond the index
        val bytes = original.toByteArray(Charsets.ISO_8859_1)
        val at = centralRecord(original) + 28
        bytes[at] = 0xFF.toByte()
        bytes[at + 1] = 0x7F
        put("broken.zip", String(bytes, Charsets.ISO_8859_1))
        fs.openRandom("home", "broken.zip").use { reader ->
            assertThrows(NasException::class.java) { ZipArchive.read(reader) }
        }
    }
}
