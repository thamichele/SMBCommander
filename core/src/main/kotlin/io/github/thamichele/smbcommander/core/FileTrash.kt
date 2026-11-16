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

    /** One deleted element: [entry] is where it is now, [original] where it was. */
    data class Item(val share: String, val slot: String, val entry: RemoteEntry, val original: String, val deletedAt: Long)

    /** True for the bin itself and everything inside it: deleted from there, things are gone for good. */
    fun isInside(path: String): Boolean {
        val p = RemotePath.normalize(path)
        return p == DIR || p.startsWith("$DIR/")
    }

    /** Moves [entry] into the bin of its share. */
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

    /** What is in the bin of [share], most recently deleted first. */
    fun contents(fs: NasFileSystem, share: String): List<Item> {
        if (fs.stat(share, DIR) == null) return emptyList()
        return fs.list(share, DIR).filter { it.isDirectory }.mapNotNull { slot ->
            runCatching {
                val inside = fs.list(share, slot.path)
                val element = inside.firstOrNull { it.name != ORIGIN } ?: return@runCatching null
                val original = inside.firstOrNull { it.name == ORIGIN }
                    ?.let { fs.openRead(share, it.path).use { s -> s.readBytes().decodeToString() } }
                    ?.takeIf { it.isNotBlank() }
                    ?: element.name
                val at = slot.name.takeWhile { it.isDigit() }.toLongOrNull() ?: slot.modified
                Item(share, slot.path, element, RemotePath.normalize(original), at)
            }.getOrNull()
        }.sortedByDescending { it.deletedAt }
    }

    /** Puts [item] back where it was, or next to it when that name was taken meanwhile; returns the path. */
    fun restore(fs: NasFileSystem, item: Item): String {
        var current = ""
        for (part in RemotePath.parent(item.original).split('/').filter { it.isNotEmpty() }) {
            current = RemotePath.child(current, part)
            if (fs.stat(item.share, current) == null) fs.createDirectory(item.share, current)
        }
        val free = EditSync.freePath(fs, item.share, item.original)
        fs.rename(item.share, item.entry.path, free, replace = false)
        runCatching { fs.delete(item.share, item.slot) }
        return free
    }

    /** Deletes [item] for good; false when it was no longer there. */
    fun delete(fs: NasFileSystem, item: Item): Boolean = fs.unlessGone(item.share, item.slot) { fs.delete(item.share, item.slot) } != null

    /** Deletes for good what has been in the bin longer than [KEEP_DAYS] days; returns how many. */
    fun purge(fs: NasFileSystem, share: String, now: Long = System.currentTimeMillis()): Int {
        val limit = now - KEEP_DAYS * 24L * 60 * 60 * 1000
        return contents(fs, share).filter { it.deletedAt < limit }.count { delete(fs, it) }
    }
}
