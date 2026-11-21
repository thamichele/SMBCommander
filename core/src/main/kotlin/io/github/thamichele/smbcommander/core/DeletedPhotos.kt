/*
 * DeletedPhotos.kt - the list of photos deleted on purpose from the backup folder
 *
 * A hidden file inside the backup folder, one "size sha256" per line. It lives on the NAS, not on the phone, so the
 * backup still skips those photos after the app is reinstalled. Hashing the photos is the caller's job.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.core

/**
 * Photos deleted from the backup folder on purpose, kept in a hidden file inside that folder ("size sha256" per line).
 * It lives on the NAS, not on the phone, so the backup still skips them after the app is reinstalled.
 */
object DeletedPhotos {

    const val FILE_NAME = ".smbcommander-eliminate.txt"

    /**
     * entry() - the line of one photo
     * @param size its size in bytes
     * @param sha256 its hash
     *
     * Return: "size sha256".
     */
    fun entry(size: Long, sha256: String) = "$size $sha256"

    /**
     * read() - reads the list from the backup folder
     * @param fs the file system
     * @param share share of the backup folder
     * @param folder the backup folder
     *
     * Context: blocking.
     * Return: the lines; empty when the file does not exist yet.
     */
    fun read(fs: NasFileSystem, share: String, folder: String): Set<String> {
        val text = fs.readText(share, RemotePath.child(folder, FILE_NAME)) ?: return emptySet()
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * add() - adds photos to the list
     * @param fs the file system
     * @param share share of the backup folder
     * @param folder the backup folder
     * @param entries lines made by [entry]
     *
     * Context: blocking.
     * Return: nothing.
     */
    fun add(fs: NasFileSystem, share: String, folder: String, entries: Collection<String>) {
        if (entries.isEmpty()) return
        val all = read(fs, share, folder) + entries
        fs.writeText(share, RemotePath.child(folder, FILE_NAME), all.joinToString("\n", postfix = "\n"))
    }

    /**
     * remove() - takes photos out of the list again, when a deletion is undone
     * @param fs the file system
     * @param share share of the backup folder
     * @param folder the backup folder
     * @param entries lines made by [entry]
     *
     * Context: blocking.
     * Return: nothing; the file is not written when none of [entries] was in it.
     */
    fun remove(fs: NasFileSystem, share: String, folder: String, entries: Collection<String>) {
        if (entries.isEmpty()) return
        val before = read(fs, share, folder)
        val all = before - entries.toSet()
        if (all.size == before.size) return
        fs.writeText(share, RemotePath.child(folder, FILE_NAME), all.joinToString("\n", postfix = if (all.isEmpty()) "" else "\n"))
    }

    /**
     * sizes() - the sizes in the list
     * @param entries the lines of the list
     *
     * Only a photo with one of these sizes needs hashing to be checked.
     *
     * Return: the sizes, without repeats.
     */
    fun sizes(entries: Set<String>): Set<Long> = entries.mapNotNullTo(HashSet()) { it.substringBefore(' ').toLongOrNull() }
}
