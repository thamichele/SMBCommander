package io.github.thamichele.smbcommander.core

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * A ZIP archive read in place on the NAS: only its index (at the end of the file) and the entries asked for travel,
 * never the whole archive. Stored and deflated entries, ZIP64 included; encrypted ones are refused.
 */
class ZipArchive private constructor(private val reader: RandomReader, val entries: List<Entry>) {

    /** One file or folder of the archive; [path] uses "/" and never starts with it. */
    data class Entry(
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val compressedSize: Long,
        val method: Int,
        val crc: Long,
        val modified: Long,
        val encrypted: Boolean,
        internal val localHeader: Long,
    ) {
        val name: String get() = path.trimEnd('/').substringAfterLast('/')
    }

    /** The elements directly inside [dir] of the archive ("" for the top), folders implied by paths included. */
    fun list(dir: String): List<Entry> {
        val base = RemotePath.normalize(dir)
        val prefix = if (base.isEmpty()) "" else "$base/"
        val seen = LinkedHashMap<String, Entry>()
        for (e in entries) {
            val p = e.path.trimEnd('/')
            if (!p.startsWith(prefix) || p == base) continue
            val rest = p.removePrefix(prefix)
            val first = rest.substringBefore('/')
            if ('/' in rest) {
                val folder = prefix + first
                seen.putIfAbsent(folder, Entry(folder, true, 0, 0, 0, 0, e.modified, false, -1))
            } else {
                seen[p] = e.copy(path = p)
            }
        }
        return seen.values.toList()
    }

    /** Every file at or under [path] (a folder) or that file itself. */
    fun filesUnder(path: String): List<Entry> {
        val p = RemotePath.normalize(path)
        return entries.filter { !it.isDirectory && (p.isEmpty() || it.path == p || it.path.startsWith("$p/")) }
    }

    /** The bytes of [entry], uncompressed; reading to the end checks the CRC. */
    fun open(entry: Entry): InputStream {
        if (entry.encrypted) throw NasException("«${entry.name}» è protetto da password: aprilo con un'app per gli archivi")
        if (entry.method != STORED && entry.method != DEFLATED) throw NasException("«${entry.name}» usa una compressione non supportata")
        val header = ByteArray(30)
        readFully(entry.localHeader, header)
        if (le32(header, 0) != LOCAL_SIG) throw NasException("Archivio danneggiato")
        val dataStart = entry.localHeader + 30 + le16(header, 26) + le16(header, 28)
        // the inflater may ask for one byte past the data to finish: it gets a zero, as java.util.zip does
        val raw: InputStream = RangeStream(reader, dataStart, entry.compressedSize, pad = entry.method == DEFLATED)
        val data = if (entry.method == DEFLATED) InflaterInputStream(raw, Inflater(true), 64 * 1024) else raw
        return CrcCheck(data, entry)
    }

    private fun readFully(position: Long, buffer: ByteArray) {
        var done = 0
        while (done < buffer.size) {
            val n = reader.read(position + done, buffer, done, buffer.size - done)
            if (n <= 0) throw NasException("Archivio troncato")
            done += n
        }
    }

    private class RangeStream(private val reader: RandomReader, private var position: Long, private var left: Long, private var pad: Boolean) : InputStream() {
        private val one = ByteArray(1)
        override fun read(): Int = if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left <= 0) {
                if (!pad) return -1
                pad = false
                b[off] = 0
                return 1
            }
            if (Thread.interrupted()) throw InterruptedException()
            val n = reader.read(position, b, off, minOf(len.toLong(), left).toInt())
            if (n <= 0) return -1
            position += n
            left -= n
            return n
        }
    }

    private class CrcCheck(input: InputStream, private val entry: Entry) : FilterInputStream(input) {
        private val crc = CRC32()
        private var count = 0L
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) { crc.update(b); count++; checkSize() } else check()
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) { crc.update(b, off, n); count += n; checkSize() } else check()
            return n
        }
        /* An entry that inflates past its declared size is a "ZIP bomb": stopped now, not after filling the NAS. */
        private fun checkSize() {
            if (count > entry.size) throw IOException("«${entry.name}» nell'archivio è danneggiato")
        }
        private fun check() {
            if (count != entry.size || crc.value != entry.crc) throw IOException("«${entry.name}» nell'archivio è danneggiato")
        }
    }

    companion object {
        private const val STORED = 0
        private const val DEFLATED = 8
        private const val LOCAL_SIG = 0x04034b50L
        private const val CENTRAL_SIG = 0x02014b50L
        private const val END_SIG = 0x06054b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L
        private const val ZIP64_END_SIG = 0x06064b50L
        private const val MAX_ENTRIES = 200_000
        private const val MAX_INDEX_BYTES = 64L * 1024 * 1024
        private const val ZIP64_END_SIZE = 56L
        private const val BAD_INDEX = "Indice dell'archivio non valido"

        /** Archives opened as folders; documents that are ZIP inside (.docx, .epub) still open with their app. */
        fun isZip(name: String) = RemotePath.extension(name).lowercase() in setOf("zip", "cbz")

        /** Reads the index of the archive; the [reader] stays open for [open] and is closed by the caller. */
        fun read(reader: RandomReader): ZipArchive {
            val size = reader.size
            val tailLength = minOf(size, 65_557L).toInt()
            val tail = ByteArray(tailLength)
            readAt(reader, size - tailLength, tail)
            var end = -1
            for (i in tailLength - 22 downTo 0) {
                if (le32(tail, i) == END_SIG) { end = i; break }
            }
            if (end < 0) throw NasException("Non è un archivio ZIP leggibile")
            var count = le16(tail, end + 10).toLong()
            var dirSize = le32(tail, end + 12)
            var dirOffset = le32(tail, end + 16)
            // ZIP64: the real values are in a record of their own, found through the locator just before
            if (end >= 20 && le32(tail, end - 20) == ZIP64_LOCATOR_SIG) {
                val zip64End = le64(tail, end - 20 + 8)
                if (zip64End < 0 || zip64End > size - ZIP64_END_SIZE) throw NasException(BAD_INDEX)
                val record = ByteArray(ZIP64_END_SIZE.toInt())
                readAt(reader, zip64End, record)
                if (le32(record, 0) == ZIP64_END_SIG) {
                    count = le64(record, 32)
                    dirSize = le64(record, 40)
                    dirOffset = le64(record, 48)
                }
            }
            if (count > MAX_ENTRIES || dirSize > MAX_INDEX_BYTES || dirOffset < 0 || dirOffset + dirSize > size) {
                throw NasException(BAD_INDEX)
            }
            val dir = ByteArray(dirSize.toInt())
            readAt(reader, dirOffset, dir)
            val entries = ArrayList<Entry>()
            var p = 0
            while (p + 46 <= dir.size && le32(dir, p) == CENTRAL_SIG) {
                val flags = le16(dir, p + 8)
                val method = le16(dir, p + 10)
                val time = le16(dir, p + 12)
                val date = le16(dir, p + 14)
                val crc = le32(dir, p + 16)
                var compressed = le32(dir, p + 20)
                var uncompressed = le32(dir, p + 24)
                val nameLength = le16(dir, p + 28)
                val extraLength = le16(dir, p + 30)
                val commentLength = le16(dir, p + 32)
                var local = le32(dir, p + 42)
                // lengths that run past the index belong to a damaged (or crafted) archive: refused as a whole
                if (p + 46 + nameLength + extraLength + commentLength > dir.size) throw NasException(BAD_INDEX)
                val nameBytes = dir.copyOfRange(p + 46, p + 46 + nameLength)
                // ZIP64 extra field: the 32-bit values set to all ones are found here, in this order
                var x = p + 46 + nameLength
                val extraEnd = x + extraLength
                while (x + 4 <= extraEnd) {
                    val id = le16(dir, x)
                    val len = le16(dir, x + 2)
                    if (x + 4 + len > extraEnd) break
                    if (id == 0x0001) {
                        var q = x + 4
                        if (uncompressed == 0xFFFFFFFFL && q + 8 <= x + 4 + len) { uncompressed = le64(dir, q); q += 8 }
                        if (compressed == 0xFFFFFFFFL && q + 8 <= x + 4 + len) { compressed = le64(dir, q); q += 8 }
                        if (local == 0xFFFFFFFFL && q + 8 <= x + 4 + len) { local = le64(dir, q) }
                    }
                    x += 4 + len
                }
                val name = decodeName(nameBytes, utf8 = flags and 0x800 != 0).replace('\\', '/').trimStart('/')
                // a name that climbs out of the archive ("../") is left out: extracting it could write anywhere
                if (name.isNotEmpty() && name.split('/').none { it == ".." }) {
                    entries += Entry(
                        path = name,
                        isDirectory = name.endsWith("/"),
                        size = uncompressed,
                        compressedSize = compressed,
                        method = method,
                        crc = crc,
                        modified = dosTime(date, time),
                        encrypted = flags and 1 != 0,
                        localHeader = local,
                    )
                }
                p += 46 + nameLength + extraLength + commentLength
            }
            return ZipArchive(reader, entries)
        }

        private fun readAt(reader: RandomReader, position: Long, buffer: ByteArray) {
            var done = 0
            while (done < buffer.size) {
                val n = reader.read(position + done, buffer, done, buffer.size - done)
                if (n <= 0) throw NasException("Archivio troncato")
                done += n
            }
        }

        private fun decodeName(bytes: ByteArray, utf8: Boolean): String {
            if (utf8) return bytes.decodeToString()
            // most archives made on phones and Macs are UTF-8 without saying so; the old Windows ones are not
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                runCatching { String(bytes, charset("IBM437")) }.getOrElse { String(bytes, Charsets.ISO_8859_1) }
            }
        }

        private fun dosTime(date: Int, time: Int): Long {
            if (date == 0) return 0
            return runCatching {
                java.time.LocalDateTime.of(
                    (date shr 9) + 1980, ((date shr 5) and 0xF).coerceIn(1, 12), (date and 0x1F).coerceIn(1, 31),
                    (time shr 11).coerceIn(0, 23), ((time shr 5) and 0x3F).coerceIn(0, 59), ((time and 0x1F) * 2).coerceIn(0, 59),
                ).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrDefault(0)
        }

        private fun le16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
        private fun le32(b: ByteArray, i: Int) = (le16(b, i).toLong()) or (le16(b, i + 2).toLong() shl 16)
        private fun le64(b: ByteArray, i: Int) = le32(b, i) or (le32(b, i + 4) shl 32)
    }
}

/** Copies files out of a [ZipArchive] into a folder of the NAS, each one checked like any upload. */
object ZipExtract {

    /**
     * Extracts [entries] into [toDir]: their folders inside the archive below [base] are recreated there. Names
     * already taken are handled as [conflict] says; a run again with the same [log] skips what is done.
     */
    fun extract(
        fs: NasFileSystem,
        archive: ZipArchive,
        entries: List<ZipArchive.Entry>,
        base: String,
        share: String,
        toDir: String,
        conflict: Conflict,
        log: TransferLog,
        progress: (name: String, done: Long, total: Long) -> Unit = { _, _, _ -> },
    ) {
        val root = RemotePath.normalize(base)
        val made = HashSet<String>()
        for (entry in entries) {
            if (Thread.interrupted()) throw InterruptedException()
            val key = "zip:" + entry.path
            if (log.has(key)) continue
            val relative = if (root.isEmpty()) entry.path else entry.path.removePrefix("$root/")
            val path = RemotePath.child(toDir, relative)
            val parent = RemotePath.parent(path)
            if (parent !in made) {
                var current = ""
                for (part in parent.split('/').filter { it.isNotEmpty() }) {
                    current = RemotePath.child(current, part)
                    if (current !in made && fs.stat(share, current) == null) fs.createDirectory(share, current)
                    made += current
                }
            }
            val target = log.remember(key) {
                val existing = fs.stat(share, path)
                when {
                    existing == null -> path
                    conflict == Conflict.Skip && !existing.isDirectory -> ""
                    conflict == Conflict.Replace && !existing.isDirectory -> path
                    else -> EditSync.freePath(fs, share, path)
                }
            }
            if (target.isEmpty()) {
                log.skip(key)
                continue
            }
            EditSync.safeUpload(fs, share, target, entry.size, { archive.open(entry) }) { done, total -> progress(entry.name, done, total) }
            log.finished(key)
        }
    }
}
