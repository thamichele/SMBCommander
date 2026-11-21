/*
 * FileTrash.kt - the bin of the file browser, one hidden folder at the top of each share
 *
 * Deleting moves the element there by a rename, into a slot of its own with a note of where it was, so it goes back
 * exactly there; slots older than KEEP_DAYS days are deleted for good. It never mixes with the cleanup's bin of the
 * gallery (CleanupTrash). Asking the user and showing the bin are the app's job.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.core

/**
 * The bin of the file browser: a hidden folder at the top of each share. Deleting moves the element there by a
 * rename, which is instant and copies nothing; each deletion gets its own slot with a small note of where the element
 * was, so it goes back exactly there. Slots older than [KEEP_DAYS] days are deleted for good.
 *
 * It is not the cleanup's bin ([CleanupTrash], inside the backup folder): the two never mix.
 */
object FileTrash {

    const val DIR = ".smbcommander-cestino-file"
    private const val ORIGIN = ".origine"
    const val KEEP_DAYS = 30
    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /** One deleted element: [entry] is where it is now, [original] where it was. */
    data class Item(val share: String, val slot: String, val entry: RemoteEntry, val original: String, val deletedAt: Long)

    /**
     * isInside() - tells whether a path is the bin or inside it
     * @param path a path inside a share
     *
     * Deleted from there, things are gone for good.
     *
     * Return: true for the bin itself and everything inside it.
     */
    fun isInside(path: String): Boolean {
        val p = RemotePath.normalize(path)
        return p == DIR || p.startsWith("$DIR/")
    }

    /**
     * discard() - moves an element into the bin of its share
     * @param fs the file system
     * @param entry the file or folder
     * @param now the time of the deletion, which names the slot
     *
     * Return: the element as it now sits in the bin.
     */
    fun discard(fs: NasFileSystem, entry: RemoteEntry, now: Long = System.currentTimeMillis()): Item {
        require(!isInside(entry.path) && entry.path.isNotEmpty()) { "«${entry.name}» non può andare nel cestino" }
        if (fs.stat(entry.share, DIR) == null) fs.createDirectory(entry.share, DIR)
        val slot = EditSync.freePath(fs, entry.share, RemotePath.child(DIR, now.toString()))
        fs.createDirectory(entry.share, slot)
        val note = entry.path.toByteArray(Charsets.UTF_8)
        fs.upload(entry.share, RemotePath.child(slot, ORIGIN), note.inputStream(), note.size.toLong())
        val target = RemotePath.child(slot, entry.name)
        try {
            fs.rename(entry.share, entry.path, target, replace = false)
        } catch (e: Exception) {
            runCatching { fs.delete(entry.share, slot) }
            throw e
        }
        return Item(entry.share, slot, entry.copy(path = target), entry.path, now)
    }

    /**
     * contents() - what is in the bin of a share
     * @param fs the file system
     * @param share the share
     *
     * A slot that cannot be read is left out instead of failing the whole list.
     *
     * Return: the elements, most recently deleted first.
     */
    fun contents(fs: NasFileSystem, share: String): List<Item> {
        if (fs.stat(share, DIR) == null) return emptyList()
        return fs.list(share, DIR).filter { it.isDirectory }.mapNotNull { slot ->
            runCatching {
                val inside = fs.list(share, slot.path)
                val element = inside.firstOrNull { it.name != ORIGIN } ?: return@runCatching null
                val original = inside.firstOrNull { it.name == ORIGIN }
                    ?.let { note -> fs.openRead(share, note.path).use { s -> s.readBytes().decodeToString() } }
                    ?.takeIf { it.isNotBlank() }
                    ?: element.name
                val at = slot.name.takeWhile { it.isDigit() }.toLongOrNull() ?: slot.modified
                Item(share, slot.path, element, RemotePath.normalize(original), at)
            }.getOrNull()
        }.sortedByDescending { it.deletedAt }
    }

    /**
     * restore() - puts an element back where it was
     * @param fs the file system
     * @param item the element in the bin
     *
     * Return: its path: where it was, or next to it when that name was taken meanwhile.
     */
    fun restore(fs: NasFileSystem, item: Item): String {
        fs.makeDirs(item.share, RemotePath.parent(item.original))
        val free = EditSync.freePath(fs, item.share, item.original)
        fs.rename(item.share, item.entry.path, free, replace = false)
        runCatching { fs.delete(item.share, item.slot) }
        return free
    }

    /**
     * delete() - deletes an element of the bin for good
     * @param fs the file system
     * @param item the element
     *
     * Return: false when it was no longer there.
     */
    fun delete(fs: NasFileSystem, item: Item): Boolean = fs.unlessGone(item.share, item.slot) { fs.delete(item.share, item.slot) } != null

    /**
     * purge() - deletes for good what has been in the bin longer than [KEEP_DAYS] days
     * @param fs the file system
     * @param share the share
     * @param now the time to count from
     *
     * Return: how many elements were deleted.
     */
    fun purge(fs: NasFileSystem, share: String, now: Long = System.currentTimeMillis()): Int {
        val limit = now - KEEP_DAYS * MILLIS_PER_DAY
        return contents(fs, share).filter { it.deletedAt < limit }.count { delete(fs, it) }
    }
}
