/*
 * ZipArchive.kt - ZIP archives on the NAS opened like folders, and extraction of their files
 *
 * Reads only the index at the end of the archive and the entries asked for, never the whole file. Stored and
 * deflated entries, ZIP64 included; encrypted ones and other methods are refused with a clear message. Names that
 * would climb out of the archive ("../") are dropped. Creating archives is not supported.
 *
 * Version: 1.28.3
 */
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

    /**
     * list() - the elements directly inside a folder of the archive
     * @param dir the folder inside the archive, "" for the top
     *
     * Return: files and folders, folders implied by longer paths included, in the order of the index.
     */
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
                seen.putIfAbsent(folder, Entry(folder, true, 0, 0, STORED, 0, e.modified, false, NO_HEADER))
            } else {
                seen[p] = e.copy(path = p)
            }
        }
        return seen.values.toList()
    }

    /**
     * filesUnder() - the files to extract for a selection
     * @param path a folder of the archive, or one file
     *
     * Return: every file at or under [path], or that file itself.
     */
    fun filesUnder(path: String): List<Entry> {
        val p = RemotePath.normalize(path)
        return entries.filter { !it.isDirectory && (p.isEmpty() || it.path == p || it.path.startsWith("$p/")) }
    }

    /**
     * open() - the bytes of one file of the archive
     * @param entry the file, from [entries] or [list]
     *
     * Reading to the end checks the CRC: a damaged entry fails there instead of passing as complete.
     *
     * Return: a stream of the uncompressed bytes; the caller closes it.
     */
    fun open(entry: Entry): InputStream {
        if (entry.encrypted) throw NasException("«${entry.name}» è protetto da password: aprilo con un'app per gli archivi")
        if (entry.method != STORED && entry.method != DEFLATED) throw NasException("«${entry.name}» usa una compressione non supportata")
        val header = ByteArray(LOCAL_HEADER_SIZE)
        readAt(reader, entry.localHeader, header)
        if (le32(header, 0) != LOCAL_SIG) throw NasException("Archivio danneggiato")
        val dataStart = entry.localHeader + LOCAL_HEADER_SIZE + le16(header, LOCAL_NAME_LENGTH) + le16(header, LOCAL_EXTRA_LENGTH)
        // the inflater may ask for one byte past the data to finish: it gets a zero, as java.util.zip does
        val raw: InputStream = RangeStream(reader, dataStart, entry.compressedSize, pad = entry.method == DEFLATED)
        val data = if (entry.method == DEFLATED) InflaterInputStream(raw, Inflater(true), INFLATE_BUFFER_BYTES) else raw
        return CrcCheck(data, entry)
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
            if (b >= 0) { crc.update(b); count++ } else check()
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) { crc.update(b, off, n); count += n } else check()
            return n
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


        /* Fixed parts of the records, APPNOTE.TXT 4.3. */
        private const val LOCAL_HEADER_SIZE = 30
        private const val LOCAL_NAME_LENGTH = 26
        private const val LOCAL_EXTRA_LENGTH = 28
        private const val CENTRAL_HEADER_SIZE = 46
        private const val END_RECORD_SIZE = 22
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_END_SIZE = 56
        private const val ZIP64_EXTRA_ID = 0x0001
        private const val EXTRA_HEADER_SIZE = 4

        /* The end record sits in the last 64 KiB plus its own size: its comment is at most 65535 bytes. */
        private const val MAX_TAIL = 65_557L
        private const val MAX_INDEX_BYTES = 64L * 1024 * 1024
        private const val INFLATE_BUFFER_BYTES = 64 * 1024

        /* A 32-bit field set to all ones: the real value is in the ZIP64 extra field. */
        private const val ZIP64_MARK = 0xFFFFFFFFL
        private const val FLAG_ENCRYPTED = 0x1
        private const val FLAG_UTF8 = 0x800
        private const val NO_HEADER = -1L
        private val ZIP_EXTENSIONS = setOf("zip", "cbz")

        /* Where the index is and how many entries it holds, from the end record or its ZIP64 version. */
        private class Index(val count: Long, val size: Long, val offset: Long)

        /**
         * isZip() - tells whether a file is opened as a folder
         * @param name the file name
         *
         * Documents that are ZIP inside (.docx, .epub) still open with their app.
         *
         * Return: true for .zip and .cbz.
         */
        fun isZip(name: String) = RemotePath.extension(name).lowercase() in ZIP_EXTENSIONS

        /**
         * read() - reads the index of an archive
         * @param reader the archive; stays open for [open] and is closed by the caller
         *
         * Context: blocking: reads the end of the file and the index.
         * Return: the archive with its entries.
         */
        fun read(reader: RandomReader): ZipArchive {
            val index = findIndex(reader)
            if (index.count > MAX_ENTRIES || index.size > MAX_INDEX_BYTES || index.offset + index.size > reader.size) {
                throw NasException("Indice dell'archivio non valido")
            }
            val dir = ByteArray(index.size.toInt())
            readAt(reader, index.offset, dir)
            val entries = ArrayList<Entry>()
            var p = 0
            while (p + CENTRAL_HEADER_SIZE <= dir.size && le32(dir, p) == CENTRAL_SIG) {
                readCentralEntry(dir, p)?.let { entries += it }
                p += CENTRAL_HEADER_SIZE + le16(dir, p + 28) + le16(dir, p + 30) + le16(dir, p + 32)
            }
            return ZipArchive(reader, entries)
        }

        /* Finds the end record in the tail of the file, then the ZIP64 record when the archive has one. */
        private fun findIndex(reader: RandomReader): Index {
            val size = reader.size
            val tailLength = minOf(size, MAX_TAIL).toInt()
            val tail = ByteArray(tailLength)
            readAt(reader, size - tailLength, tail)
            val end = (tailLength - END_RECORD_SIZE downTo 0).firstOrNull { le32(tail, it) == END_SIG }
                ?: throw NasException("Non è un archivio ZIP leggibile")
            val plain = Index(le16(tail, end + 10).toLong(), le32(tail, end + 12), le32(tail, end + 16))
            // ZIP64: the real values are in a record of their own, found through the locator just before
            val locator = end - ZIP64_LOCATOR_SIZE
            if (locator < 0 || le32(tail, locator) != ZIP64_LOCATOR_SIG) return plain
            val record = ByteArray(ZIP64_END_SIZE)
            readAt(reader, le64(tail, locator + 8), record)
            if (le32(record, 0) != ZIP64_END_SIG) return plain
            return Index(le64(record, 32), le64(record, 40), le64(record, 48))
        }

        /* One entry of the central directory at [p], or null when its name would climb out of the archive. */
        private fun readCentralEntry(dir: ByteArray, p: Int): Entry? {
            val flags = le16(dir, p + 8)
            val nameLength = le16(dir, p + 28)
            val sizes = longArrayOf(le32(dir, p + 24), le32(dir, p + 20), le32(dir, p + 42))
            val nameStart = p + CENTRAL_HEADER_SIZE
            readZip64Extra(dir, nameStart + nameLength, le16(dir, p + 30), sizes)
            val nameBytes = dir.copyOfRange(nameStart, nameStart + nameLength)
            val name = decodeName(nameBytes, utf8 = flags and FLAG_UTF8 != 0).replace('\\', '/').trimStart('/')
            // a name that climbs out of the archive ("../") is left out: extracting it could write anywhere
            if (name.isEmpty() || name.split('/').any { it == ".." }) return null
            return Entry(
                path = name,
                isDirectory = name.endsWith("/"),
                size = sizes[0],
                compressedSize = sizes[1],
                method = le16(dir, p + 10),
                crc = le32(dir, p + 16),
                modified = dosTime(le16(dir, p + 14), le16(dir, p + 12)),
                encrypted = flags and FLAG_ENCRYPTED != 0,
                localHeader = sizes[2],
            )
        }

        /*
         * The ZIP64 extra field: the values of [sizes] (uncompressed, compressed, local header offset) set to all
         * ones are found there, in this order, and replaced in place.
         */
        private fun readZip64Extra(dir: ByteArray, start: Int, length: Int, sizes: LongArray) {
            var x = start
            val extraEnd = start + length
            while (x + EXTRA_HEADER_SIZE <= extraEnd) {
                val id = le16(dir, x)
                val len = le16(dir, x + 2)
                if (id == ZIP64_EXTRA_ID) readZip64Values(dir, x + EXTRA_HEADER_SIZE, x + EXTRA_HEADER_SIZE + len, sizes)
                x += EXTRA_HEADER_SIZE + len
            }
        }

        private fun readZip64Values(dir: ByteArray, start: Int, end: Int, sizes: LongArray) {
            var q = start
            for (i in sizes.indices) {
                if (sizes[i] != ZIP64_MARK || q + Long.SIZE_BYTES > end) continue
                sizes[i] = le64(dir, q)
                q += Long.SIZE_BYTES
            }
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
     * extract() - copies files out of an archive into a folder of the NAS
     * @param fs the file system
     * @param archive the archive, open
     * @param entries the files to extract
     * @param base the folder of the archive they are taken from: their path below it is recreated
     * @param share share of the destination
     * @param toDir destination folder
     * @param conflict what to do with names already taken
     * @param log what an earlier run of the same extraction already did
     * @param progress the file being written, its bytes so far and its size
     *
     * Context: blocking; stops with InterruptedException when the thread is interrupted.
     * Return: nothing.
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
            fs.makeDirs(share, RemotePath.parent(path), made)
            val target = log.remember(key) { Transfers.fileTarget(fs, share, path, conflict).orEmpty() }
            if (target.isEmpty()) {
                log.skip(key)
                continue
            }
            EditSync.safeUpload(fs, share, target, entry.size, { archive.open(entry) }) { done, total -> progress(entry.name, done, total) }
            log.finished(key)
        }
    }
}
