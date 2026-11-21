/*
 * EditSync.kt - opening a NAS file in another app and bringing the changes back safely
 *
 * Decides whether a file edited on the phone can simply be uploaded, or changed on the NAS meanwhile, or vanished
 * from it. Holds the safe upload every feature uses (temporary name, read back, SHA-256 check, one rename) and the
 * choice of free names ("name (2).ext"). Downloading the file and asking the user are the app's job.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.core

import java.io.File
import java.io.InputStream

/**
 * A NAS file opened for editing: downloaded to [localFile] and handed to another app.
 * [baseHash], [remoteSize] and [remoteModified] describe the version both sides last agreed on.
 */
data class EditSession(
    val id: String,
    val share: String,
    val path: String,
    val localFile: File,
    val baseHash: String,
    val remoteSize: Long,
    val remoteModified: Long,
    val openedAt: Long,
)

sealed interface SyncDecision {
    /** The local copy did not change: nothing to upload. */
    data object Unchanged : SyncDecision

    /** Changed locally, untouched on the NAS: safe to overwrite. */
    data object Upload : SyncDecision

    /** Changed on both sides: the user decides. */
    data class Conflict(val remote: RemoteEntry) : SyncDecision

    /** Changed locally but the file is gone from the NAS (deleted or renamed meanwhile). */
    data object RemoteMissing : SyncDecision
}

/** The rules that bring an edited file back to the NAS without losing anybody's changes. */
object EditSync {

    /* A failed check of an upload is tried once more before it is reported. */
    private const val UPLOAD_ATTEMPTS = 2

    /* The first number a taken name gets: "name (2).ext". */
    private const val FIRST_FREE_NUMBER = 2

    /**
     * decide() - what to do with a file edited on the phone
     * @param session the file as it was when it was opened
     * @param localHash SHA-256 of the local copy now
     * @param remote the file on the NAS now, or null when it is gone
     *
     * Return: the decision; only [SyncDecision.Upload] can be carried out without asking the user.
     */
    fun decide(session: EditSession, localHash: String, remote: RemoteEntry?): SyncDecision = when {
        localHash == session.baseHash -> SyncDecision.Unchanged
        remote == null -> SyncDecision.RemoteMissing
        remote.size != session.remoteSize || remote.modified != session.remoteModified -> SyncDecision.Conflict(remote)
        else -> SyncDecision.Upload
    }

    /**
     * safeOverwrite() - uploads a local file over [path] without ever leaving a half-written file there
     * @param fs the file system
     * @param share the share
     * @param path the file to write
     * @param local the bytes to send
     * @param progress bytes sent so far and the total
     *
     * The data goes to a hidden temporary file in the same folder, which then replaces the target in one rename.
     *
     * Context: blocking.
     * Return: the target as the NAS now reports it.
     */
    fun safeOverwrite(fs: NasFileSystem, share: String, path: String, local: File, progress: Progress = { _, _ -> }): RemoteEntry =
        safeUpload(fs, share, path, local.length(), { local.inputStream() }, progress)

    /**
     * safeUpload() - like [safeOverwrite], for content that is not a local file (a document picked from another app)
     * @param fs the file system
     * @param share the share
     * @param path the file to write
     * @param length bytes expected, or [UNKNOWN_SIZE]
     * @param open opens the content; called again for the second attempt
     * @param progress bytes sent so far and the total
     *
     * Before the rename the temporary file is read back from the NAS: only when its SHA-256 equals the hash of the
     * bytes sent does it replace the target. A mismatch is retried once, then reported; the target is never touched.
     *
     * Context: blocking.
     * Return: the target as the NAS now reports it.
     */
    fun safeUpload(
        fs: NasFileSystem,
        share: String,
        path: String,
        length: Long,
        open: () -> InputStream,
        progress: Progress = { _, _ -> },
    ): RemoteEntry {
        val name = RemotePath.name(path)
        val temp = RemotePath.child(RemotePath.parent(path), RemotePath.uploadTempName(name))
        var attempt = 1
        while (true) {
            try {
                val sent = open().use { input -> Integrity.HashingInputStream(input).also { fs.upload(share, temp, it, length, progress) } }
                if (length >= 0 && sent.bytes != length) {
                    throw Integrity.mismatch(name, "letti ${sent.bytes} byte invece di $length: il file è cambiato durante il caricamento")
                }
                if (Integrity.remoteSha256(fs, share, temp) == sent.sha256()) {
                    fs.rename(share, temp, path, replace = true)
                    break
                }
                runCatching { fs.delete(share, temp) }
                if (attempt++ >= UPLOAD_ATTEMPTS) throw Integrity.mismatch(name, "il contenuto arrivato sul NAS è diverso")
            } catch (e: Exception) {
                runCatching { fs.delete(share, temp) }
                throw e
            }
        }
        return fs.stat(share, path) ?: throw NasException("Il file caricato non risulta sul NAS")
    }

    /**
     * freePath() - a name that nothing on the NAS uses yet
     * @param fs the file system
     * @param share the share
     * @param path the name wanted
     *
     * Return: [path] itself when free, else the first free "name (2).ext", "name (3).ext"...
     */
    fun freePath(fs: NasFileSystem, share: String, path: String): String {
        if (fs.stat(share, path) == null) return path
        return nextFreePath(fs, share, path)
    }

    /**
     * nextFreePath() - like [freePath] for a [path] already known to be taken, sparing one look on the NAS
     * @param fs the file system
     * @param share the share
     * @param path the name wanted, already taken
     *
     * Return: the first free "name (2).ext", "name (3).ext"...
     */
    fun nextFreePath(fs: NasFileSystem, share: String, path: String): String {
        val parent = RemotePath.parent(path)
        val name = RemotePath.name(path)
        var n = FIRST_FREE_NUMBER
        while (true) {
            val candidate = RemotePath.child(parent, RemotePath.withSuffix(name, n.toString()))
            if (fs.stat(share, candidate) == null) return candidate
            n++
        }
    }

    /**
     * freeCopyPath() - a free name for a copy kept next to [path]
     * @param fs the file system
     * @param share the share
     * @param path the original
     * @param suffix what tells the copy apart, such as "modificato"
     *
     * Return: the first free "name (suffix).ext", "name (suffix 2).ext"...
     */
    fun freeCopyPath(fs: NasFileSystem, share: String, path: String, suffix: String): String {
        val parent = RemotePath.parent(path)
        val name = RemotePath.name(path)
        var n = 1
        while (true) {
            val candidate = RemotePath.child(parent, RemotePath.withSuffix(name, if (n == 1) suffix else "$suffix $n"))
            if (fs.stat(share, candidate) == null) return candidate
            n++
        }
    }

    /**
     * sha256() - the SHA-256 of a local file
     * @param file the file
     *
     * Return: the hash in lowercase hex.
     */
    fun sha256(file: File): String = file.inputStream().use { Integrity.sha256(it) }
}
