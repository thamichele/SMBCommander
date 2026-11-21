/*
 * NasFileSystem.kt - what the app can do with the files of a NAS, and the small helpers built on top of it
 *
 * Declares the interface every screen and job talks to (list, read, write, rename, delete), the data it hands back
 * (RemoteEntry, DiskSpace) and the checks around the connection settings (NasServer, ServerIdentity). The SMB
 * implementation lives in SmbNasFileSystem, the routing between several NAS in MultiNasFileSystem: this file opens
 * no connection.
 *
 * The extension functions at the end are the steps many features repeat (create a chain of folders, read or write a
 * small list kept on the NAS, drop a folder left empty), written once here.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.core

import java.io.ByteArrayInputStream
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

    /**
     * accepts() - tells whether the NAS that answered is the one paired
     * @param paired identity stored at pairing time; empty when nothing is paired yet
     * @param presented identity the NAS sent now; null when it sent none
     *
     * Return: true when nothing is paired yet or [presented] is the paired identity.
     */
    fun accepts(paired: String, presented: String?): Boolean =
        paired.isEmpty() || (presented != null && paired.equals(presented, ignoreCase = true))
}

/** A file or folder on the NAS, as a listing reports it. */
data class RemoteEntry(
    val share: String,
    /** Path inside the share, see [RemotePath]. */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Last write time in epoch milliseconds. */
    val modified: Long,
) {
    val name: String get() = RemotePath.name(path).ifEmpty { NasShares.shownName(share) }
    val extension: String get() = if (isDirectory) "" else RemotePath.extension(name)
}

/** Called with bytes transferred so far and the total (-1 when unknown). */
typealias Progress = (done: Long, total: Long) -> Unit

/** Size passed to a [Progress] while the total is not known. */
const val UNKNOWN_SIZE = -1L

/** Reads a file at any position without downloading it: what video playback and video frames need. */
interface RandomReader : Closeable {
    val size: Long

    /**
     * read() - reads part of the file without moving any cursor
     * @param position where to start, in bytes from the start of the file
     * @param buffer where the bytes go
     * @param offset first index of [buffer] written
     * @param length at most this many bytes
     *
     * Context: blocking.
     * Return: the bytes read, or -1 at the end of the file.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int
}

/**
 * File operations on the NAS. All functions block: call them from a background dispatcher. Errors come as
 * [NasException] with a message ready for the user, or as the SMB library's own exception for a refused operation.
 */
interface NasFileSystem : Closeable {

    /**
     * listShares() - the disk shares the NAS announces
     *
     * Administrative shares (ADMIN$, C$) are left out. Shares hidden from browsing are not announced at all: those
     * can only be opened by name.
     *
     * Return: share names in alphabetical order, any case.
     */
    fun listShares(): List<String>

    /**
     * list() - what a folder holds, one level only
     * @param share the share
     * @param path the folder inside it, "" for the top
     *
     * Return: files and folders in the order the NAS gives them, "." and ".." left out.
     */
    fun list(share: String, path: String): List<RemoteEntry>

    /**
     * stat() - looks up one file or folder
     * @param share the share
     * @param path its path
     *
     * Return: the entry at [path], or null when it does not exist.
     */
    fun stat(share: String, path: String): RemoteEntry?

    /**
     * openRead() - opens a file to read it from the start
     * @param share the share
     * @param path the file
     *
     * Return: a stream the caller closes; closing it also closes the file on the NAS.
     */
    fun openRead(share: String, path: String): InputStream

    /**
     * download() - copies a whole file into [out]
     * @param share the share
     * @param path the file
     * @param out where the bytes go; flushed, not closed
     * @param progress bytes copied so far and the total
     *
     * Return: nothing.
     */
    fun download(share: String, path: String, out: OutputStream, progress: Progress = { _, _ -> })

    /**
     * upload() - writes [input] to [path], creating or truncating the file
     * @param share the share
     * @param path the file to write
     * @param input the bytes, read to the end; not closed
     * @param length how many bytes to expect, or [UNKNOWN_SIZE]
     * @param progress bytes written so far and [length]
     *
     * Not atomic: callers that must never leave half a file use [EditSync.safeUpload].
     *
     * Return: nothing.
     */
    fun upload(share: String, path: String, input: InputStream, length: Long, progress: Progress = { _, _ -> })

    /**
     * createDirectory() - creates one folder, whose parent must exist
     * @param share the share
     * @param path the new folder
     *
     * Return: nothing.
     */
    fun createDirectory(share: String, path: String)

    /**
     * delete() - deletes a file, or a folder with everything inside it
     * @param share the share
     * @param path what to delete
     *
     * Return: nothing.
     */
    fun delete(share: String, path: String)

    /**
     * rename() - renames or moves inside one share, instantly and without copying
     * @param share the share
     * @param from the current path
     * @param to the new path
     * @param replace overwrite a file already at [to]
     *
     * Return: nothing.
     */
    fun rename(share: String, from: String, to: String, replace: Boolean = false)

    /**
     * openRandom() - opens a file to read it at any position
     * @param share the share
     * @param path the file
     *
     * Return: a reader the caller closes.
     */
    fun openRandom(share: String, path: String): RandomReader

    /**
     * copyFile() - copies one file to [to], creating or truncating it
     * @param fromShare share of the file to copy
     * @param from its path
     * @param toShare share of the copy
     * @param to path of the copy
     * @param progress bytes copied so far and the total
     *
     * When both are on the same NAS the copy is done by the NAS itself where possible, so the data does not travel
     * through the phone.
     *
     * Return: the SHA-256 of the bytes when they were streamed through the phone, null when the NAS copied them.
     */
    fun copyFile(fromShare: String, from: String, toShare: String, to: String, progress: Progress = { _, _ -> }): String?

    /**
     * space() - size of the disk behind [share] and how much of it is free
     * @param share the share
     *
     * Return: the figures the NAS reports, or null when it reports none.
     */
    fun space(share: String): DiskSpace? = null
}

/** Bytes of a disk: all of it and the part still free for this user. */
data class DiskSpace(val total: Long, val free: Long) {
    val used: Long get() = (total - free).coerceAtLeast(0)
}

/** An error of the NAS or of the connection, with a message the user can read. */
class NasException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * unlessGone() - runs a step on a file that may have left the NAS in the meantime
 * @param share the share of the file
 * @param path the file
 * @param block the step
 *
 * A file deleted or moved from another device makes a batch skip that file and go on; any other error is thrown as
 * it is. The extra stat() is paid only when [block] fails.
 *
 * Return: what [block] returned, or null when it failed because the file is no longer there.
 */
fun <T : Any> NasFileSystem.unlessGone(share: String, path: String, block: () -> T): T? = try {
    block()
} catch (e: Exception) {
    val gone = try {
        stat(share, path) == null
    } catch (_: Exception) {
        // the NAS cannot even be asked: the original error says more than this one
        false
    }
    if (gone) null else throw e
}

/**
 * makeDirs() - creates [dir] and every folder above it that is missing
 * @param share the share
 * @param dir the folder that must exist
 * @param known folders already checked or created in this run, updated here; null to check them all
 *
 * A long run (the backup, an extraction) passes the same set to every call, so each folder costs one look on the
 * NAS at most.
 *
 * Return: nothing.
 */
fun NasFileSystem.makeDirs(share: String, dir: String, known: MutableSet<String>? = null) {
    var current = ""
    for (part in RemotePath.normalize(dir).split('/')) {
        if (part.isEmpty()) continue
        current = RemotePath.child(current, part)
        if (known != null && current in known) continue
        if (stat(share, current) == null) createDirectory(share, current)
        known?.add(current)
    }
}

/**
 * removeIfEmpty() - deletes a folder once nothing is left inside it
 * @param share the share
 * @param dir the folder
 *
 * Return: nothing; a folder already gone or still holding something stays as it is.
 */
fun NasFileSystem.removeIfEmpty(share: String, dir: String) {
    if (stat(share, dir) != null && list(share, dir).isEmpty()) delete(share, dir)
}

/**
 * readText() - reads a small text file kept on the NAS, such as the lists of the backup folder
 * @param share the share
 * @param path the file
 *
 * Return: its content as UTF-8, or null when the file does not exist.
 */
fun NasFileSystem.readText(share: String, path: String): String? {
    if (stat(share, path) == null) return null
    return openRead(share, path).use { it.readBytes() }.decodeToString()
}

/**
 * writeText() - replaces a small text file on the NAS without ever leaving it half written
 * @param share the share
 * @param path the file
 * @param text the new content, written as UTF-8
 *
 * Return: nothing.
 */
fun NasFileSystem.writeText(share: String, path: String, text: String) {
    val bytes = text.toByteArray()
    EditSync.safeUpload(this, share, path, bytes.size.toLong(), { ByteArrayInputStream(bytes) })
}
