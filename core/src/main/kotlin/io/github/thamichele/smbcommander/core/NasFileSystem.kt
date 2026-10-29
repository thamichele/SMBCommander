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
)

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

/**
 * File operations on the NAS. All functions block: call them from a background dispatcher.
 */
interface NasFileSystem : Closeable {

    /** Disk shares visible to the user, hidden admin shares (ending in "$") excluded. */
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
}

class NasException(message: String, cause: Throwable? = null) : Exception(message, cause)
