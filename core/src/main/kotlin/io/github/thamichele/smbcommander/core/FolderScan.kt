package io.github.thamichele.smbcommander.core

/** Walks a folder and everything under it: its size, and a search by name. */
object FolderScan {

    /** What a folder holds, all levels included. */
    data class Totals(val files: Int = 0, val folders: Int = 0, val bytes: Long = 0) {
        operator fun plus(e: RemoteEntry) =
            if (e.isDirectory) copy(folders = folders + 1) else copy(files = files + 1, bytes = bytes + e.size)
    }

    /** Hidden and system names (".name", "@Recycle", "#recycle"): the browser hides them too unless asked. */
    fun isHidden(name: String) = name.startsWith(".") || name.startsWith("@") || name.startsWith("#")

    /**
     * Adds up the files under [path], calling [onProgress] after each folder with the totals so far. Stops at once
     * when [stopped] says so. A subfolder that cannot be read is skipped and counted in [onUnreadable].
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
     * Looks for names containing [query] (any case) under [path], at most [maxDepth] levels down, nearer folders
     * first. Each match goes to [onFound] as soon as it is seen; the search ends after [limit] matches or when
     * [stopped] says so. Hidden elements are left out unless [includeHidden].
     */
    fun search(
        fs: NasFileSystem,
        share: String,
        path: String,
        query: String,
        includeHidden: Boolean = false,
        maxDepth: Int = 30,
        limit: Int = 2000,
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
