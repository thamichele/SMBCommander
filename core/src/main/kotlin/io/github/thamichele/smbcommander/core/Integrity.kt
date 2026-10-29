package io.github.thamichele.smbcommander.core

import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Every byte that is uploaded, copied, moved or downloaded is checked: the SHA-256 of what was sent is compared with
 * the SHA-256 of what arrived. A file that does not match is never kept, and nothing is deleted before its copy matched.
 */
object Integrity {

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    /** Reads the whole NAS file and hashes it. */
    fun remoteSha256(fs: NasFileSystem, share: String, path: String): String = fs.openRead(share, path).use { sha256(it) }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Hashes and counts the bytes read through it, so data is hashed on its way without a second read. */
    class HashingInputStream(input: InputStream) : FilterInputStream(input) {
        private val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                digest.update(b.toByte())
                bytes++
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                digest.update(b, off, n)
                bytes += n
            }
            return n
        }

        override fun skip(n: Long): Long = throw UnsupportedOperationException("skipped bytes would not be hashed")
        override fun markSupported(): Boolean = false

        /** The hash of everything read so far; call once, at the end. */
        fun sha256(): String = hex(digest.digest())
    }

    /**
     * Downloads [path] into [out] and returns the SHA-256 of the bytes written. Fails when fewer or more bytes arrived
     * than the NAS reports for the file.
     */
    fun download(fs: NasFileSystem, share: String, path: String, out: OutputStream, progress: Progress = { _, _ -> }): String {
        val expected = fs.stat(share, path)?.size ?: throw NasException("«${RemotePath.name(path)}» non esiste più sul NAS")
        val input = HashingInputStream(fs.openRead(share, path))
        input.use {
            val buffer = ByteArray(1 shl 20)
            progress(0, expected)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val read = it.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                progress(it.bytes, expected)
            }
            out.flush()
        }
        if (input.bytes != expected) throw mismatch(RemotePath.name(path), "ricevuti ${input.bytes} byte su $expected")
        return input.sha256()
    }

    fun mismatch(name: String, detail: String) =
        NasException("Verifica di «$name» non riuscita ($detail): il file non è stato tenuto")
}
