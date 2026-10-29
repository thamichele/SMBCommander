package io.github.thamichele.smbcommander.core

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

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

    /** Like [safeOverwrite], for content that is not a local file (a document picked from another app). */
    fun safeUpload(
        fs: NasFileSystem,
        share: String,
        path: String,
        length: Long,
        open: () -> InputStream,
        progress: Progress = { _, _ -> },
    ): RemoteEntry {
        val temp = RemotePath.child(RemotePath.parent(path), RemotePath.uploadTempName(RemotePath.name(path)))
        try {
            open().use { fs.upload(share, temp, it, length, progress) }
            fs.rename(share, temp, path, replace = true)
        } catch (e: Exception) {
            runCatching { fs.delete(share, temp) }
            throw e
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

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
