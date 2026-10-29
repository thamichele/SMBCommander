package io.github.thamichele.smbcommander.core

import java.io.InputStream

/** Where a photo of the backup goes: nowhere, because the NAS already has it, or to [Upload.path]. */
sealed interface BackupPlacement {
    data class AlreadyThere(val path: String) : BackupPlacement
    data class Upload(val path: String) : BackupPlacement

    companion object {
        /**
         * Looks at "photo.jpg", then "photo_2.jpg", "photo_3.jpg"... and stops at the first free name, or at a file that
         * is the same photo. Same size alone is not enough: then both are hashed, so after a reinstall the photos
         * already on the NAS are recognised and a different photo with the same name is never skipped.
         * Remote files are read only when the size matches, and the local one at most once.
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
                    fs.openRead(share, candidate).use { sha256(it) } == local
                ) {
                    return AlreadyThere(candidate)
                }
                n++
            }
        }

        fun sha256(input: InputStream): String = Integrity.sha256(input)
    }
}
