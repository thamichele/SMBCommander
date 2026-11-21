/*
 * GalleryIndex.kt - what the gallery knows about the backup folder, and when each photo was taken
 *
 * GalleryIndex holds the photos and videos of each folder below the backup folder and changes only where the NAS
 * changed; GalleryDates works out the month of a photo from its folders, its name or its date. Walking the NAS and
 * storing the index on the phone are the app's job: this file does no I/O.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.core

import java.util.Calendar

/**
 * What the gallery knows about the backup folder: the photos and videos of each folder below it. A refresh lists the
 * folders again and replaces each one's content as it arrives, so the gallery only changes where the NAS changed;
 * folders that are gone are dropped at the end, and a folder that could not be read keeps what it had.
 */
class GalleryIndex(initial: Collection<RemoteEntry> = emptyList()) {

    private val byDir = HashMap<String, List<RemoteEntry>>()

    /** Folders with nothing inside: the albums still to be filled. */
    private val emptyDirs = HashSet<String>()

    init {
        val (dirs, media) = initial.partition { it.isDirectory }
        media.groupBy { RemotePath.parent(it.path) }.forEach { (dir, list) -> byDir[dir] = list }
        dirs.mapTo(emptyDirs) { it.path }
    }

    /**
     * replace() - sets the media found directly in a folder
     * @param dir the folder
     * @param media what it holds now
     * @param isEmptyAlbum the folder holds neither media nor other folders: it stays known as an album to fill
     *
     * Return: true when that changed anything.
     */
    @Synchronized
    fun replace(dir: String, media: List<RemoteEntry>, isEmptyAlbum: Boolean = false): Boolean {
        val emptyChanged = if (isEmptyAlbum && media.isEmpty()) emptyDirs.add(dir) else emptyDirs.remove(dir)
        val before = byDir[dir].orEmpty()
        if (media.isEmpty()) return (byDir.remove(dir) != null) || emptyChanged
        if (before.size == media.size && before.toSet() == media.toSet()) return emptyChanged
        byDir[dir] = media
        return true
    }

    /**
     * retain() - after a full walk, drops the folders that were not seen
     * @param seen the folders the walk listed
     * @param failed the folders the walk could not list: what lies in them is unknown, so it stays as it was
     *
     * Return: true when something was dropped.
     */
    @Synchronized
    fun retain(seen: Set<String>, failed: Set<String>): Boolean {
        val isGone = { dir: String -> dir !in seen && failed.none { dir == it || dir.startsWith("$it/") } }
        val gone = byDir.keys.filter(isGone)
        gone.forEach { byDir.remove(it) }
        return emptyDirs.removeAll(isGone) || gone.isNotEmpty()
    }

    /**
     * remove() - forgets the media that match, after they were deleted or moved away
     * @param predicate true for the media to forget
     *
     * Return: nothing.
     */
    @Synchronized
    fun remove(predicate: (RemoteEntry) -> Boolean) {
        for (dir in byDir.keys.toList()) {
            val left = byDir.getValue(dir).filterNot(predicate)
            if (left.isEmpty()) byDir.remove(dir) else byDir[dir] = left
        }
    }

    /**
     * addEmpty() - makes a folder just created known as an empty album, before the next walk finds it
     * @param dir the new folder, normalized
     *
     * Return: nothing.
     */
    @Synchronized
    fun addEmpty(dir: String) {
        if (dir !in byDir) emptyDirs += dir
    }

    /**
     * move() - follows a photo moved on the NAS, without waiting for the next walk
     * @param entry the photo as the index knows it
     * @param to its new path
     *
     * Safe to repeat: a transfer run again after a dropped connection reports the same moves twice. A folder left
     * without photos and without known subfolders stays as an empty album, as it does on the NAS.
     *
     * Return: nothing.
     */
    @Synchronized
    fun move(entry: RemoteEntry, to: String) {
        val from = RemotePath.parent(entry.path)
        val left = byDir[from]?.filterNot { it.path == entry.path }
        if (left != null && left.isEmpty()) {
            byDir.remove(from)
            if (byDir.keys.none { it.startsWith("$from/") }) emptyDirs += from
        } else if (left != null) {
            byDir[from] = left
        }
        val dir = RemotePath.parent(to)
        val there = byDir[dir].orEmpty()
        if (there.none { it.path == to }) byDir[dir] = there + entry.copy(path = to)
        emptyDirs -= dir
    }

    /**
     * all() - every photo and video known
     *
     * Return: a new list, in no particular order.
     */
    @Synchronized
    fun all(): List<RemoteEntry> = byDir.values.flatten()

    /**
     * folders() - the media of each folder that has any, by folder path
     *
     * Return: a copy, safe to read while a walk goes on.
     */
    @Synchronized
    fun folders(): Map<String, List<RemoteEntry>> = HashMap(byDir)

    /**
     * emptyFolders() - the folders known to hold nothing: albums still to be filled
     *
     * Return: a copy of their paths.
     */
    @Synchronized
    fun emptyFolders(): Set<String> = HashSet(emptyDirs)

    /**
     * stored() - everything worth keeping on the phone between two runs of the app
     * @param share the share of the backup folder, for the entries of the empty folders
     *
     * Return: the media, followed by one directory entry per empty folder; what the constructor takes back.
     */
    @Synchronized
    fun stored(share: String): List<RemoteEntry> =
        byDir.values.flatten() + emptyDirs.map { RemoteEntry(share, it, true, 0, 0) }
}

/**
 * When a photo or video was taken, as far as its path tells: a "2026/10" or "2026-10" folder (the backup of this app,
 * QuMagie, most camera uploads), else a date in the file name ("IMG_20261029_…", "2026-10-29 12.30.00.jpg"),
 * else the file date on the NAS.
 */
object GalleryDates {

    private val YEAR_MONTH_FOLDER = Regex("""^((?:19|20)\d{2})[-_. ]?(0[1-9]|1[0-2])(?:[-_. ]?(0[1-9]|[12]\d|3[01]))?(?:\D.*)?$""")
    private val YEAR_FOLDER = Regex("""^(?:19|20)\d{2}$""")
    private val MONTH_FOLDER = Regex("""^(0?[1-9]|1[0-2])(?:\D.*)?$""")
    private val NAME_DATE = Regex("""(?:^|\D)((?:19|20)\d{2})[-_.]?(0[1-9]|1[0-2])[-_.]?(0[1-9]|[12]\d|3[01])(?:[-_. T]?([01]\d|2[0-3])[-_.:]?([0-5]\d)[-_.:]?([0-5]\d)|(?=\D|$))""")

    data class Taken(val year: Int, val month: Int, /** For ordering inside the month, newest first. */ val sortKey: Long)

    /**
     * of() - when a photo or video was taken
     * @param entry the photo or video
     *
     * Return: its year and month, and a key "yyyyMMddHHmmss" to order it inside the month.
     */
    fun of(entry: RemoteEntry): Taken {
        val parts = entry.path.split('/')
        val name = parts.last()
        val fromName = NAME_DATE.find(name)?.let { m ->
            val g = m.groupValues
            val key = (g[1] + g[2] + g[3] + g[4].ifEmpty { "00" } + g[5].ifEmpty { "00" } + g[6].ifEmpty { "00" }).toLong()
            Triple(g[1].toInt(), g[2].toInt(), key)
        }
        val fromFolder = folderMonth(parts.dropLast(1))
        val cal = Calendar.getInstance().apply { timeInMillis = entry.modified }
        val fileKey = "%04d%02d%02d%02d%02d%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND),
        ).toLong()
        return when {
            // the folder says the month; the name, when it agrees, orders the photos inside it
            fromFolder != null -> Taken(
                fromFolder.first, fromFolder.second,
                fromName?.takeIf { it.first == fromFolder.first && it.second == fromFolder.second }?.third ?: fileKey,
            )
            fromName != null -> Taken(fromName.first, fromName.second, fromName.third)
            else -> Taken(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, fileKey)
        }
    }

    /**
     * namesMonth() - whether a chain of folders names a month, as "2026/10" or "2019-03-14" do
     * @param folders the folder names, outermost first
     *
     * Return: true when photos in that folder get their month from the folder names.
     */
    fun namesMonth(folders: List<String>): Boolean = folderMonth(folders) != null

    /** The deepest folder (or pair of folders) that names a month. */
    private fun folderMonth(folders: List<String>): Pair<Int, Int>? {
        for (i in folders.indices.reversed()) {
            val f = folders[i]
            YEAR_MONTH_FOLDER.find(f)?.let { return it.groupValues[1].toInt() to it.groupValues[2].toInt() }
            if (i > 0 && YEAR_FOLDER.matches(folders[i - 1])) {
                MONTH_FOLDER.find(f)?.let { return folders[i - 1].toInt() to it.groupValues[1].toInt() }
            }
        }
        return null
    }
}
