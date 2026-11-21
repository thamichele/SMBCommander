/*
 * FolderScan.kt - walks a folder and everything under it: its size, and a search by name
 *
 * Breadth first, so nearer folders come first and a stop leaves a useful partial result. A subfolder that cannot be
 * read is skipped; only the folder asked for must be readable. Showing the results is the app's job.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.core

/** Size of a folder and search by name, over every level below it. */
object FolderScan {

    /* Levels below the folder a search goes, and matches it stops at: enough for any real NAS, bounded anyway. */
    private const val SEARCH_DEPTH = 30
    private const val SEARCH_LIMIT = 2000

    /** What a folder holds, all levels included. */
    data class Totals(val files: Int = 0, val folders: Int = 0, val bytes: Long = 0) {
        operator fun plus(e: RemoteEntry) =
            if (e.isDirectory) copy(folders = folders + 1) else copy(files = files + 1, bytes = bytes + e.size)
    }

    /**
     * isHidden() - tells whether a name is hidden or belongs to the system
     * @param name a file or folder name
     *
     * Return: true for ".name", "@Recycle", "#recycle".
     */
    fun isHidden(name: String) = name.startsWith(".") || name.startsWith("@") || name.startsWith("#")

    /**
     * measure() - adds up the files under a folder
     * @param fs the file system
     * @param share the share
     * @param path the folder
     * @param stopped asked before each folder: true stops at once
     * @param onUnreadable called for each subfolder that cannot be read, and is skipped
     * @param onProgress called after each folder with the totals so far
     *
     * Context: blocking.
     * Return: the totals, partial when stopped.
     */
    fun measure(
        fs: NasFileSystem,
        share: String,
        path: String,
        stopped: () -> Boolean = { false },
        onUnreadable: () -> Unit = {},
        onProgress: (Totals) -> Unit = {},
    ): Totals {
        var totals = Totals()
        val pending = ArrayDeque<String>().apply { add(RemotePath.normalize(path)) }
        while (pending.isNotEmpty() && !stopped()) {
            val dir = pending.removeFirst()
            val children = try {
                fs.list(share, dir)
            } catch (e: Exception) {
                if (dir == RemotePath.normalize(path)) throw e
                onUnreadable()
                continue
            }
            for (child in children) {
                totals += child
                if (child.isDirectory) pending.addLast(child.path)
            }
            onProgress(totals)
        }
        return totals
    }

    /**
     * search() - looks for names containing [query], any case, under a folder
     * @param fs the file system
     * @param share the share
     * @param path the folder
     * @param query the text to look for; blank finds nothing
     * @param includeHidden also look at hidden elements
     * @param maxDepth levels below [path] to go at most
     * @param limit matches to stop at
     * @param stopped asked before each folder: true stops at once
     * @param onFolder the folder being read
     * @param onFound each match, as soon as it is seen
     *
     * Context: blocking.
     * Return: how many matches were found.
     */
    fun search(
        fs: NasFileSystem,
        share: String,
        path: String,
        query: String,
        includeHidden: Boolean = false,
        maxDepth: Int = SEARCH_DEPTH,
        limit: Int = SEARCH_LIMIT,
        stopped: () -> Boolean = { false },
        onFolder: (String) -> Unit = {},
        onFound: (RemoteEntry) -> Unit,
    ): Int {
        val needle = query.trim()
        if (needle.isEmpty()) return 0
        var found = 0
        val pending = ArrayDeque<Pair<String, Int>>().apply { add(RemotePath.normalize(path) to 0) }
        while (pending.isNotEmpty() && !stopped() && found < limit) {
            val (dir, depth) = pending.removeFirst()
            onFolder(dir)
            val children = try {
                fs.list(share, dir)
            } catch (e: Exception) {
                if (depth == 0) throw e
                continue
            }
            for (child in children) {
                if (!includeHidden && isHidden(child.name)) continue
                if (child.name.contains(needle, ignoreCase = true)) {
                    onFound(child)
                    if (++found >= limit) break
                }
                if (child.isDirectory && depth < maxDepth) pending.addLast(child.path to depth + 1)
            }
        }
        return found
    }
}
