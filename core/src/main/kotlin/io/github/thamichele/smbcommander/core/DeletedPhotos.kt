package io.github.thamichele.smbcommander.core

import java.io.ByteArrayInputStream

/**
 * Photos deleted from the backup folder on purpose, kept in a hidden file inside that folder ("size sha256" per line).
 * It lives on the NAS, not on the phone, so the backup still skips them after the app is reinstalled.
 */
object DeletedPhotos {

    const val FILE_NAME = ".smbcommander-eliminate.txt"

    fun entry(size: Long, sha256: String) = "$size $sha256"

    fun read(fs: NasFileSystem, share: String, folder: String): Set<String> {
        val path = RemotePath.child(folder, FILE_NAME)
        if (fs.stat(share, path) == null) return emptySet()
        return fs.openRead(share, path).use { it.readBytes() }.decodeToString()
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun add(fs: NasFileSystem, share: String, folder: String, entries: Collection<String>) {
        if (entries.isEmpty()) return
        val all = read(fs, share, folder) + entries
        val bytes = all.joinToString("\n", postfix = "\n").toByteArray()
        EditSync.safeUpload(fs, share, RemotePath.child(folder, FILE_NAME), bytes.size.toLong(), { ByteArrayInputStream(bytes) })
    }

    /** Sizes in the list: only a photo with one of these sizes needs hashing to be checked. */
    fun sizes(entries: Set<String>): Set<Long> = entries.mapNotNullTo(HashSet()) { it.substringBefore(' ').toLongOrNull() }
}
