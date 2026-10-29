package io.github.thamichele.smbcommander.core

import java.util.Calendar

/**
 * What the gallery knows about the backup folder: the photos and videos of each folder below it. A refresh lists the
 * folders again and replaces each one's content as it arrives, so the gallery only changes where the NAS changed;
 * folders that are gone are dropped at the end, and a folder that could not be read keeps what it had.
 */
class GalleryIndex(initial: Collection<RemoteEntry> = emptyList()) {

    private val byDir = HashMap<String, List<RemoteEntry>>()

    init {
        initial.groupBy { RemotePath.parent(it.path) }.forEach { (dir, media) -> byDir[dir] = media }
    }

    /** Sets the media found directly in [dir]; true when that changed anything. */
    @Synchronized
    fun replace(dir: String, media: List<RemoteEntry>): Boolean {
        val before = byDir[dir].orEmpty()
        if (media.isEmpty()) return byDir.remove(dir) != null
        if (before.size == media.size && before.toSet() == media.toSet()) return false
        byDir[dir] = media
        return true
    }

    /**
     * After a full walk: drops the folders that were not seen, unless they lie in a folder that failed to list
     * (their state is unknown, so they stay as they were). True when something was dropped.
     */
    @Synchronized
    fun retain(seen: Set<String>, failed: Set<String>): Boolean {
        val gone = byDir.keys.filter { dir -> dir !in seen && failed.none { dir == it || dir.startsWith("$it/") } }
        gone.forEach { byDir.remove(it) }
        return gone.isNotEmpty()
    }

    @Synchronized
    fun remove(predicate: (RemoteEntry) -> Boolean) {
        for (dir in byDir.keys.toList()) {
            val left = byDir.getValue(dir).filterNot(predicate)
            if (left.isEmpty()) byDir.remove(dir) else byDir[dir] = left
        }
    }

    @Synchronized
    fun all(): List<RemoteEntry> = byDir.values.flatten()
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
