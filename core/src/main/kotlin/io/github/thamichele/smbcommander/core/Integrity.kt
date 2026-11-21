/*
 * Integrity.kt - SHA-256 checks of every byte that moves between the phone and the NAS
 *
 * Hashes streams on their way (HashingInputStream), whole NAS files, and downloads with a check of their length.
 * Deciding what to do when a check fails is the caller's job: this file only measures and reports.
 *
 * Version: 1.28.3
 */
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

    private const val ALGORITHM = "SHA-256"
    private const val HASH_BUFFER_BYTES = 1 shl 16
    private const val DOWNLOAD_BUFFER_BYTES = 1 shl 20
    private const val HEX_DIGITS = "0123456789abcdef"
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F

    /**
     * sha256() - hashes a stream to its end
     * @param input the stream; read to the end, not closed
     *
     * Context: blocking; stops with InterruptedException when the thread is interrupted.
     * Return: the hash in lowercase hex.
     */
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance(ALGORITHM)
        val buffer = ByteArray(HASH_BUFFER_BYTES)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return hex(digest.digest())
    }

    /**
     * remoteSha256() - reads a whole NAS file and hashes it
     * @param fs the file system
     * @param share the share
     * @param path the file
     *
     * Context: blocking.
     * Return: the hash in lowercase hex.
     */
    fun remoteSha256(fs: NasFileSystem, share: String, path: String): String = fs.openRead(share, path).use { sha256(it) }

    /**
     * hex() - writes bytes as lowercase hex
     * @param bytes the bytes
     *
     * Return: two characters per byte.
     */
    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt()
            out.append(HEX_DIGITS[(v shr NIBBLE_BITS) and NIBBLE_MASK]).append(HEX_DIGITS[v and NIBBLE_MASK])
        }
        return out.toString()
    }

    /** Hashes and counts the bytes read through it, so data is hashed on its way without a second read. */
    class HashingInputStream(input: InputStream) : FilterInputStream(input) {
        private val digest = MessageDigest.getInstance(ALGORITHM)
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

        /**
         * sha256() - the hash of everything read so far
         *
         * Call once, at the end: the digest is reset afterwards.
         *
         * Return: the hash in lowercase hex.
         */
        fun sha256(): String = hex(digest.digest())
    }

    /**
     * download() - downloads a NAS file and hashes it on the way
     * @param fs the file system
     * @param share the share
     * @param path the file
     * @param out where the bytes go; flushed, not closed
     * @param progress bytes so far and the size of the file
     *
     * Fails when fewer or more bytes arrived than the NAS reports for the file.
     *
     * Context: blocking.
     * Return: the SHA-256 of the bytes written.
     */
    fun download(fs: NasFileSystem, share: String, path: String, out: OutputStream, progress: Progress = { _, _ -> }): String {
        val expected = fs.stat(share, path)?.size ?: throw NasException("«${RemotePath.name(path)}» non esiste più sul NAS")
        val input = HashingInputStream(fs.openRead(share, path))
        input.use {
            val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
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

    /**
     * mismatch() - the error of a check that failed
     * @param name the file
     * @param detail what did not match
     *
     * Return: the exception to throw, with a message for the user.
     */
    fun mismatch(name: String, detail: String) =
        NasException("Verifica di «$name» non riuscita ($detail): il file non è stato tenuto")
}
