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

object EditSync {

    fun decide(session: EditSession, localHash: String, remote: RemoteEntry?): SyncDecision = when {
        localHash == session.baseHash -> SyncDecision.Unchanged
        remote == null -> SyncDecision.RemoteMissing
        remote.size != session.remoteSize || remote.modified != session.remoteModified -> SyncDecision.Conflict(remote)
        else -> SyncDecision.Upload
    }

    /**
     * Uploads [local] to [path] without ever leaving a half-written file there: the data goes to a hidden temporary
     * file in the same folder, which then replaces the target in one rename.
     * Returns the target as the NAS now reports it.
     */
    fun safeOverwrite(fs: NasFileSystem, share: String, path: String, local: File, progress: Progress = { _, _ -> }): RemoteEntry =
        safeUpload(fs, share, path, local.length(), { local.inputStream() }, progress)

    /**
     * Like [safeOverwrite], for content that is not a local file (a document picked from another app).
     * Before the rename the temporary file is read back from the NAS: only when its SHA-256 equals the hash of the
     * bytes sent does it replace the target. A mismatch is retried once, then reported; the target is never touched.
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
                if (attempt++ >= 2) throw Integrity.mismatch(name, "il contenuto arrivato sul NAS è diverso")
            } catch (e: Exception) {
                runCatching { fs.delete(share, temp) }
                throw e
            }
        }
        return fs.stat(share, path) ?: throw NasException("Il file caricato non risulta sul NAS")
    }

    /** [path] itself when free, else the first free "name (2).ext", "name (3).ext"... */
    fun freePath(fs: NasFileSystem, share: String, path: String): String {
        if (fs.stat(share, path) == null) return path
        val parent = RemotePath.parent(path)
        val name = RemotePath.name(path)
        var n = 2
        while (true) {
            val candidate = RemotePath.child(parent, RemotePath.withSuffix(name, n.toString()))
            if (fs.stat(share, candidate) == null) return candidate
            n++
        }
    }

    /** First free "name (suffix).ext", "name (suffix 2).ext"... next to [path]. */
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

    fun sha256(file: File): String = file.inputStream().use { Integrity.sha256(it) }
}
