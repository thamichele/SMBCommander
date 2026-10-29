package io.github.thamichele.smbcommander.core

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/** In-memory NAS: files by "share/path", directories implicit. */
class FakeNasFileSystem : NasFileSystem {
    val files = linkedMapOf<String, ByteArray>()
    val modified = mutableMapOf<String, Long>()
    var clock = 1_000L
    var failUploadAfter: Int = -1
    /** Uploads and copies whose content arrives altered, as a faulty disk or link would. */
    var corruptWrites: Int = 0

    private fun key(share: String, path: String) = "$share/${RemotePath.normalize(path)}"

    fun put(share: String, path: String, content: String) {
        files[key(share, path)] = content.toByteArray()
        modified[key(share, path)] = ++clock
    }

    fun read(share: String, path: String): String? = files[key(share, path)]?.decodeToString()

    override fun listShares() = files.keys.map { it.substringBefore('/') }.distinct()

    override fun list(share: String, path: String): List<RemoteEntry> {
        val dir = RemotePath.normalize(path)
        val prefix = if (dir.isEmpty()) "$share/" else "$share/$dir/"
        return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
            .map { stat(share, RemotePath.child(dir, it))!! }
    }

    override fun stat(share: String, path: String): RemoteEntry? {
        val k = key(share, path)
        files[k]?.let { return RemoteEntry(share, RemotePath.normalize(path), false, it.size.toLong(), modified.getValue(k)) }
        return if (files.keys.any { it.startsWith("$k/") }) RemoteEntry(share, RemotePath.normalize(path), true, 0, 0) else null
    }

    override fun openRead(share: String, path: String): InputStream =
        ByteArrayInputStream(files[key(share, path)] ?: throw NasException("not found"))

    override fun download(share: String, path: String, out: OutputStream, progress: Progress) {
        openRead(share, path).copyTo(out)
    }

    override fun upload(share: String, path: String, input: InputStream, length: Long, progress: Progress) {
        val bytes = input.readBytes()
        if (failUploadAfter >= 0) {
            files[key(share, path)] = bytes.copyOf(failUploadAfter)
            modified[key(share, path)] = ++clock
            throw NasException("connection lost")
        }
        files[key(share, path)] = corrupt(bytes)
        modified[key(share, path)] = ++clock
    }

    private fun corrupt(bytes: ByteArray): ByteArray {
        if (corruptWrites <= 0 || bytes.isEmpty()) return bytes
        corruptWrites--
        return bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }
    }

    override fun createDirectory(share: String, path: String) {}

    override fun delete(share: String, path: String) {
        val k = key(share, path)
        files.keys.removeAll { it == k || it.startsWith("$k/") }
    }

    override fun rename(share: String, from: String, to: String, replace: Boolean) {
        val f = key(share, from)
        val t = key(share, to)
        if (!replace && t in files) throw NasException("exists")
        files[t] = files.remove(f) ?: throw NasException("not found")
        modified[t] = modified.remove(f)!!
    }

    override fun openRandom(share: String, path: String): RandomReader {
        val bytes = files[key(share, path)] ?: throw NasException("not found")
        return object : RandomReader {
            override val size = bytes.size.toLong()
            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= size) return -1
                val n = minOf(length.toLong(), size - position).toInt()
                System.arraycopy(bytes, position.toInt(), buffer, offset, n)
                return n
            }
            override fun close() {}
        }
    }

    override fun copyFile(fromShare: String, from: String, toShare: String, to: String, progress: Progress): String? {
        files[key(toShare, to)] = corrupt((files[key(fromShare, from)] ?: throw NasException("not found")).copyOf())
        modified[key(toShare, to)] = ++clock
        return null
    }

    override fun close() {}
}
