package io.github.thamichele.smbcommander.core

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** Connection settings typed in by the user. Never hard-code real values: they stay on the device only. */
data class NasServer(
    val host: String,
    val username: String,
    val password: String,
    val domain: String = "",
    /** Identity the NAS showed when it was paired, see [ServerIdentity]; empty until the first connection. */
    val serverId: String = "",
) {
    /** Keeps the password out of anything that prints the settings (logs, crash reports). */
    override fun toString(): String = "NasServer(host=$host, username=$username, domain=$domain, serverId=$serverId)"
}

/**
 * The NAS is recognised by the server GUID it sends before any password is exchanged. A different machine answering
 * at the same address (another network with the same IP range, a spoofed name) is refused before it can receive the
 * login, so it cannot collect the password hash. On Samba, and so on QNAP, the GUID comes from the NetBIOS name: it
 * only changes when the NAS is renamed, and then connecting again from the settings pairs it anew.
 */
object ServerIdentity {
    /** True when nothing is paired yet or [presented] is the paired identity. */
    fun accepts(paired: String, presented: String?): Boolean =
        paired.isEmpty() || (presented != null && paired.equals(presented, ignoreCase = true))
}

data class RemoteEntry(
    val share: String,
    /** Path inside the share, see [RemotePath]. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Last write time in epoch milliseconds. */
    val modified: Long,
) {
    val name: String get() = RemotePath.name(path).ifEmpty { share }
    val extension: String get() = if (isDirectory) "" else RemotePath.extension(name)
}

/** Called with bytes transferred so far and the total (-1 when unknown). */
typealias Progress = (done: Long, total: Long) -> Unit

/** Reads a file at any position without downloading it: what video playback and video frames need. */
interface RandomReader : Closeable {
    val size: Long

    /** Up to [length] bytes at [position] into [buffer]; -1 at the end of the file. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * File operations on the NAS. All functions block: call them from a background dispatcher.
 */
interface NasFileSystem : Closeable {

    /**
     * Disk shares the NAS announces, administrative ones (ADMIN$, C$) excluded. Shares hidden from browsing are not
     * announced at all: those can only be opened by name.
     */
    fun listShares(): List<String>

    fun list(share: String, path: String): List<RemoteEntry>

    /** The entry at [path], or null when it does not exist. */
    fun stat(share: String, path: String): RemoteEntry?

    fun openRead(share: String, path: String): InputStream

    fun download(share: String, path: String, out: OutputStream, progress: Progress = { _, _ -> })

    /** Writes [input] to [path], creating or truncating the file. */
    fun upload(share: String, path: String, input: InputStream, length: Long, progress: Progress = { _, _ -> })

    fun createDirectory(share: String, path: String)

    /** Deletes a file, or a directory with everything inside it. */
    fun delete(share: String, path: String)

    fun rename(share: String, from: String, to: String, replace: Boolean = false)

    fun openRandom(share: String, path: String): RandomReader

    /**
     * Copies one file to [to], creating or truncating it. When both are on the same NAS the copy is done by the NAS
     * itself where possible, so the data does not travel through the phone.
     */
    fun copyFile(fromShare: String, from: String, toShare: String, to: String, progress: Progress = { _, _ -> })
}

class NasException(message: String, cause: Throwable? = null) : Exception(message, cause)
