/*
 * BackupPlacement.kt - where a photo of the backup goes, or whether the NAS already has it
 *
 * Looks at the name the photo would take and at its numbered variants, hashing only files of the same size, so a
 * reinstalled app recognises the photos already sent and a different photo with a taken name is never skipped.
 * Reading the photo on the phone is the caller's job.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.core

/** Where a photo of the backup goes: nowhere, because the NAS already has it, or to [Upload.path]. */
sealed interface BackupPlacement {
    data class AlreadyThere(val path: String) : BackupPlacement
    data class Upload(val path: String) : BackupPlacement

    companion object {
        /**
         * find() - where a photo of the backup goes
         * @param fs the file system
         * @param share the share of the backup folder
         * @param target the path the photo would take
         * @param size its size
         * @param localHash computes its SHA-256 on the phone; called at most once, only when needed
         *
         * Looks at "photo.jpg", then "photo_2.jpg", "photo_3.jpg"... and stops at the first free name, or at a file that
         * is the same photo. Same size alone is not enough: then both are hashed, so after a reinstall the photos
         * already on the NAS are recognised and a different photo with the same name is never skipped.
         * Remote files are read only when the size matches, and the local one at most once.
         *
         * Context: blocking.
         * Return: [AlreadyThere] with the copy found, or [Upload] with the first free name.
         */
        fun find(fs: NasFileSystem, share: String, target: String, size: Long, localHash: () -> String): BackupPlacement {
            val parent = RemotePath.parent(target)
            val name = RemotePath.name(target)
            val local by lazy(localHash)
            var n = 1
            while (true) {
                val candidate = if (n == 1) target else RemotePath.child(parent, RemotePath.numbered(name, n))
                val existing = fs.stat(share, candidate) ?: return Upload(candidate)
                if (!existing.isDirectory && existing.size == size &&
                    fs.openRead(share, candidate).use { Integrity.sha256(it) } == local
                ) {
                    return AlreadyThere(candidate)
                }
                n++
            }
        }
    }
}
