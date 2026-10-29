package io.github.thamichele.smbcommander.core

/**
 * Copy and move between folders of the NAS, files or whole folders. A name already taken gets " (2)", " (3)"...
 * Each copied file is compared with its original by SHA-256 before it takes its name, and a move deletes the original
 * only after the whole copy matched.
 */
object Transfers {

    /** Called with the file being copied, its bytes so far and its size. */
    fun interface Listener {
        fun onProgress(name: String, done: Long, total: Long)
    }

    /** Copies [source] into the folder [toDir] of [toShare] and returns the new path. */
    fun copy(fs: NasFileSystem, source: RemoteEntry, toShare: String, toDir: String, listener: Listener = Listener { _, _, _ -> }): String {
        checkNotInside(source, toShare, toDir)
        val target = EditSync.freePath(fs, toShare, RemotePath.child(toDir, source.name))
        copyTo(fs, source, toShare, target, listener)
        return target
    }

    /**
     * Moves [source] into [toDir]. Inside one share it is a rename, instant even for big folders; between shares it
     * is a copy, and the original is deleted only once the copy is complete.
     */
    fun move(fs: NasFileSystem, source: RemoteEntry, toShare: String, toDir: String, listener: Listener = Listener { _, _, _ -> }): String {
        checkNotInside(source, toShare, toDir)
        if (toShare == source.share && RemotePath.normalize(toDir) == RemotePath.parent(source.path)) return source.path
        val target = EditSync.freePath(fs, toShare, RemotePath.child(toDir, source.name))
        if (toShare == source.share) {
            fs.rename(source.share, source.path, target, replace = false)
        } else {
            copyTo(fs, source, toShare, target, listener)
            fs.delete(source.share, source.path)
        }
        return target
    }

    private fun copyTo(fs: NasFileSystem, source: RemoteEntry, toShare: String, target: String, listener: Listener) {
        if (source.isDirectory) {
            fs.createDirectory(toShare, target)
            for (child in fs.list(source.share, source.path)) {
                copyTo(fs, child, toShare, RemotePath.child(target, child.name), listener)
            }
            return
        }
        // through a hidden temporary name, as uploads do: a copy cut short never looks like a complete file
        val temp = RemotePath.child(RemotePath.parent(target), RemotePath.uploadTempName(RemotePath.name(target)))
        try {
            val streamed = fs.copyFile(source.share, source.path, toShare, temp) { done, total -> listener.onProgress(source.name, done, total) }
            listener.onProgress("Verifica di ${source.name}", -1, -1)
            val original = streamed ?: Integrity.remoteSha256(fs, source.share, source.path)
            if (Integrity.remoteSha256(fs, toShare, temp) != original) throw Integrity.mismatch(source.name, "la copia è diversa dall'originale")
            fs.rename(toShare, temp, target, replace = true)
        } catch (e: Exception) {
            runCatching { fs.delete(toShare, temp) }
            throw e
        }
    }

    private fun checkNotInside(source: RemoteEntry, toShare: String, toDir: String) {
        if (!source.isDirectory || toShare != source.share) return
        val dir = RemotePath.normalize(toDir)
        if (dir == source.path || dir.startsWith(source.path + "/")) {
            throw NasException("Non puoi mettere «${source.name}» dentro sé stessa")
        }
    }
}
