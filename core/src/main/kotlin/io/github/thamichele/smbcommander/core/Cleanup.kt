/*
 * Cleanup.kt - the rules of the quick cleanup of the gallery, its list of kept photos and its bin
 *
 * Picks the photos and videos worth a second look (screenshots, messaging apps, bursts, copies) from their names,
 * folders and sizes alone, keeps on the NAS the list of those the user chose to keep, and moves the discarded ones
 * into a hidden bin inside the backup folder. Looking at the pixels is PhotoKinds' job; the screens are the app's.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.core

import java.util.Calendar

/**
 * Why a photo or video deserves a second look in the quick cleanup. Real camera photos almost never get one: only
 * when they are part of a burst or a copy of another file.
 */
data class CleanupReason(val kind: Kind, val detail: String = "") {
    /** In the order the cleanup shows them: the likeliest rubbish first. */
    enum class Kind(val label: String) {
        Screenshot("Screenshot"),
        ScreenRecording("Registrazione dello schermo"),
        Messaging("Da un'app di messaggi"),
        Animated("GIF o sticker"),
        Tiny("Immagine molto piccola"),
        Downloaded("Scaricata o salvata da un'app"),
        Duplicate("Doppione"),
        Edited("Copia modificata"),
        Burst("Raffica di foto"),
        ShortVideo("Video brevissimo"),
    }

    val text: String get() = if (detail.isEmpty()) kind.label else "${kind.label}: $detail"
}

/** Which photos and videos the quick cleanup shows, and which ones are copies of each other. */
object CleanupRules {

    private val SCREENSHOT = Regex("""screen[ _-]?shot|schermata|bildschirmfoto|captura de pantalla|capture d.?.cran""", RegexOption.IGNORE_CASE)
    private val RECORDING = Regex("""screen[ _-]?record|screenrecord|registrazione( dello)? schermo|^record_|^rec_""", RegexOption.IGNORE_CASE)
    private val WHATSAPP_NAME = Regex("""^(IMG|VID|STK|PTT|AUD)-\d{8}-WA\d+""", RegexOption.IGNORE_CASE)
    private val MESSAGING_NAME = Regex("""^(signal-|fb_img_|received_|snapchat-|telegram|tg_)""", RegexOption.IGNORE_CASE)
    private val MESSAGING_DIR = mapOf(
        "whatsapp" to "WhatsApp", "telegram" to "Telegram", "signal" to "Signal", "messenger" to "Messenger",
        "instagram" to "Instagram", "snapchat" to "Snapchat", "facebook" to "Facebook", "viber" to "Viber",
        "discord" to "Discord", "reddit" to "Reddit", "twitter" to "Twitter", "tiktok" to "TikTok",
    )
    private val DOWNLOAD_DIR = Regex("""^(download|downloads|saved|salvate|scaricati|pinterest|pictures/saved)$""", RegexOption.IGNORE_CASE)
    // names apps invent: a long run of hex digits or a UUID, with no date in it
    private val GENERATED_NAME = Regex("""^[0-9a-f]{24,}|^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-""", RegexOption.IGNORE_CASE)
    private val EDITED = Regex("""[-_ ]edit(ed)?\b|[-_ ]modificat|~\d+\.|\(\d+\)\.|[-_ ]copy\b|^copia di |snapseed|picsart|_collage""", RegexOption.IGNORE_CASE)
    private val CAMERA_NAME = Regex("""^(IMG|PXL|DSC|DSCN|DSCF|P\d{3}|_DSC|MVIMG|VID|MOV|GOPR|DJI|CIMG|SAM|HDR)[_-]?\d""", RegexOption.IGNORE_CASE)

    private const val TINY_IMAGE = 60L * 1024
    private const val SHORT_VIDEO = 2L * 1024 * 1024
    private const val BURST_SECONDS = 2
    private const val MIN_BURST = 2
    private const val SMALL_ANIMATION = 512L * 1024
    private const val MILLIS_PER_SECOND = 1000L

    /* Digits of a sort key "yyyyMMddHHmmss" from GalleryDates. */
    private const val SORT_KEY_LENGTH = 14

    /**
     * classify() - the media of the backup folder that should be checked
     * @param media every photo and video of the folder
     * @param root the backup folder
     *
     * Return: the media picked, each with the reason it was picked, in the order of [media].
     */
    fun classify(media: List<RemoteEntry>, root: String): Map<RemoteEntry, CleanupReason> {
        val result = LinkedHashMap<RemoteEntry, CleanupReason>()
        for (e in media) single(e, root)?.let { result[e] = it }
        // bursts: camera photos of the same folder taken within a couple of seconds of each other
        val photos = media.filter { it.extension in PHOTO && it !in result && CAMERA_NAME.containsMatchIn(it.name) }
        for (folder in photos.groupBy { RemotePath.parent(it.path) }.values) {
            val timed = folder.mapNotNull { e -> seconds(e)?.let { e to it } }.sortedBy { it.second }
            var start = 0
            for (i in 1..timed.size) {
                if (i < timed.size && timed[i].second - timed[i - 1].second <= BURST_SECONDS) continue
                val burst = timed.subList(start, i)
                if (burst.size >= MIN_BURST) burst.forEach { (e, _) -> result[e] = CleanupReason(CleanupReason.Kind.Burst, "${burst.size} foto in pochi secondi") }
                start = i
            }
        }
        return result
    }

    /**
     * duplicates() - groups of copies of the same photo or video
     * @param media every photo and video of the backup folder
     *
     * Copies of the same photo or video: the same name, or the same name with a different ending ("IMG_1234 (1)",
     * "IMG_1234_2", "IMG_1234 - Copia", "IMG_1234.HEIC" next to "IMG_1234.JPG"), in one folder or in two. Each group
     * comes with the likeliest original first: the shortest path, then the biggest file. Names too short or too common
     * to tell anything ("foto_1", forty "Screenshot (n)") are left out.
     *
     * Return: the groups, each with two to [MAX_GROUP] elements.
     */
    fun duplicates(media: List<RemoteEntry>): List<List<RemoteEntry>> =
        media.groupBy { (if (it.extension in VIDEO) "v:" else "p:") + stem(it.name) }
            .filter { (key, group) -> group.size in 2..MAX_GROUP && key.length - 2 >= MIN_STEM }
            .values
            .map { group -> group.sortedWith(compareBy<RemoteEntry> { it.path.count { c -> c == '/' } }.thenByDescending { it.size }.thenBy { it.path }) }

    /**
     * stem() - the part of a name that copies of one file share
     * @param name the file name
     *
     * Return: the name without extension and without the endings copies get, lowercase:
     * "IMG_1234 (2).jpg" -> "img_1234".
     */
    fun stem(name: String): String {
        var s = name.substringBeforeLast('.', name).lowercase().trim()
        s = COPY_PREFIX.replace(s, "")
        while (true) {
            val next = COPY_SUFFIX.replace(s, "").trim()
            if (next == s || next.isEmpty()) break
            s = next
        }
        // one short number at the end ("_1", "-2"), but not the day of a date or the seconds of a time ("2026-10-29")
        val numbered = NUMBER_SUFFIX.replace(s, "")
        return if (numbered.isNotEmpty() && numbered != s && !DATE_END.containsMatchIn(numbered)) numbered else s
    }

    private val COPY_PREFIX = Regex("""^(copia di|copy of|kopie von)\s+""")
    private val COPY_SUFFIX = Regex("""(\s*\(\d{1,3}\)|\s*-?\s*(copia|copy|kopie|copie)(\s*\d{1,2})?)$""")
    private val NUMBER_SUFFIX = Regex("""[ _~-]\d{1,2}$""")
    private val DATE_END = Regex("""\d{2}[-_. ]\d{2}$""")
    private const val MIN_STEM = 5
    private const val MAX_GROUP = 6

    private fun single(e: RemoteEntry, root: String): CleanupReason? {
        val name = e.name
        val folders = relative(RemotePath.parent(e.path), root).split('/').filter { it.isNotEmpty() }
        val video = e.extension in VIDEO
        fun inFolder(r: Regex) = folders.any { r.containsMatchIn(it) }
        return when {
            SCREENSHOT.containsMatchIn(name) || inFolder(SCREENSHOT) ->
                CleanupReason(if (video) CleanupReason.Kind.ScreenRecording else CleanupReason.Kind.Screenshot)
            RECORDING.containsMatchIn(name) || inFolder(RECORDING) -> CleanupReason(CleanupReason.Kind.ScreenRecording)
            WHATSAPP_NAME.containsMatchIn(name) -> CleanupReason(CleanupReason.Kind.Messaging, "WhatsApp")
            MESSAGING_NAME.containsMatchIn(name) -> CleanupReason(CleanupReason.Kind.Messaging)
            folders.any { f -> MESSAGING_DIR.keys.any { f.contains(it, ignoreCase = true) } } -> CleanupReason(
                CleanupReason.Kind.Messaging,
                folders.firstNotNullOf { f -> MESSAGING_DIR.entries.firstOrNull { f.contains(it.key, ignoreCase = true) }?.value },
            )
            e.extension == "gif" || (e.extension == "webp" && e.size < SMALL_ANIMATION) -> CleanupReason(CleanupReason.Kind.Animated)
            !video && e.size in 1 until TINY_IMAGE -> CleanupReason(CleanupReason.Kind.Tiny)
            folders.any { DOWNLOAD_DIR.matches(it) } || GENERATED_NAME.containsMatchIn(name) -> CleanupReason(CleanupReason.Kind.Downloaded)
            EDITED.containsMatchIn(name) -> CleanupReason(CleanupReason.Kind.Edited)
            video && e.size in 1 until SHORT_VIDEO -> CleanupReason(CleanupReason.Kind.ShortVideo)
            else -> null
        }
    }

    /** When it was taken, to the second, from the name ("IMG_20261029_101500"), else the file date. */
    private fun seconds(e: RemoteEntry): Long? {
        val key = GalleryDates.of(e).sortKey.toString()
        if (key.length != SORT_KEY_LENGTH) return null
        val cal = Calendar.getInstance().apply {
            clear()
            set(key.substring(0, 4).toInt(), key.substring(4, 6).toInt() - 1, key.substring(6, 8).toInt(),
                key.substring(8, 10).toInt(), key.substring(10, 12).toInt(), key.substring(12, 14).toInt())
        }
        return cal.timeInMillis / MILLIS_PER_SECOND
    }

    /**
     * relative() - a path seen from inside a folder
     * @param path the path inside the share
     * @param root the folder
     *
     * Return: [path] below [root], "" for [root] itself, or [path] unchanged when [root] is the top of the share.
     */
    fun relative(path: String, root: String): String {
        val r = RemotePath.normalize(root)
        val p = RemotePath.normalize(path)
        return if (r.isEmpty()) p else p.removePrefix("$r/").let { if (it == r) "" else it }
    }

    private val PHOTO = setOf("jpg", "jpeg", "heic", "heif", "png", "webp", "dng")
    private val VIDEO = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
}

/**
 * Photos the user looked at in the quick cleanup and chose to keep, in a hidden file of the backup folder
 * ("size<TAB>path inside the folder" per line). On the NAS, so the choices survive a reinstall and a second phone.
 * A kept photo moved to another folder is still recognised by its name and size.
 */
class KeptPhotos(lines: Collection<String> = emptyList()) {

    private val byPath = HashSet<String>()
    private val byName = HashSet<String>()

    init {
        lines.forEach(::addLine)
    }

    val lines: Set<String> @Synchronized get() = byPath.toSet()

    /**
     * contains() - tells whether a photo was kept, here or before it was moved
     * @param entry the photo
     * @param root the backup folder
     *
     * Return: true when its path, or its name and size, are in the list.
     */
    @Synchronized
    fun contains(entry: RemoteEntry, root: String): Boolean =
        line(entry, root) in byPath || "${entry.size}\t${entry.name.lowercase()}" in byName

    /**
     * containsPath() - tells whether a photo was kept at this very path
     * @param entry the photo
     * @param root the backup folder
     *
     * Return: true only for this path: a copy with the same name elsewhere does not count.
     */
    @Synchronized
    fun containsPath(entry: RemoteEntry, root: String): Boolean = line(entry, root) in byPath

    /**
     * add() - adds a photo to the list
     * @param entry the photo
     * @param root the backup folder
     *
     * Return: nothing.
     */
    @Synchronized
    fun add(entry: RemoteEntry, root: String) = addLine(line(entry, root))

    /**
     * remove() - takes a photo out of the list
     * @param entry the photo
     * @param root the backup folder
     *
     * Return: nothing.
     */
    @Synchronized
    fun remove(entry: RemoteEntry, root: String) {
        val l = line(entry, root)
        byPath -= l
        // the name index is rebuilt: another kept file may share name and size
        byName.clear()
        byPath.forEach { byName += nameKey(it) }
    }

    private fun addLine(l: String) {
        if (!l.contains('\t')) return
        byPath += l
        byName += nameKey(l)
    }

    private fun nameKey(l: String) = l.substringBefore('\t') + "\t" + l.substringAfterLast('/').substringAfter('\t').lowercase()

    private fun line(entry: RemoteEntry, root: String) = lineOf(entry, root)

    companion object {
        const val FILE_NAME = ".smbcommander-tenute.txt"

        /**
         * read() - reads the list from the backup folder
         * @param fs the file system
         * @param share share of the backup folder
         * @param folder the backup folder
         *
         * Context: blocking.
         * Return: the list; empty when the file does not exist yet.
         */
        fun read(fs: NasFileSystem, share: String, folder: String): KeptPhotos {
            val text = fs.readText(share, RemotePath.child(folder, FILE_NAME)) ?: return KeptPhotos()
            return KeptPhotos(text.lines().filter { it.isNotBlank() })
        }

        /**
         * write() - writes the list, merged with what another phone may have added meanwhile
         * @param fs the file system
         * @param share share of the backup folder
         * @param folder the backup folder
         * @param kept the list as this phone knows it
         * @param removed lines this phone took out, which must not come back from the merge
         *
         * Context: blocking.
         * Return: nothing.
         */
        fun write(fs: NasFileSystem, share: String, folder: String, kept: KeptPhotos, removed: Collection<String> = emptyList()) {
            val merged = read(fs, share, folder).lines - removed.toSet() + kept.lines
            fs.writeText(share, RemotePath.child(folder, FILE_NAME), merged.sorted().joinToString("\n", postfix = "\n"))
        }

        /**
         * lineOf() - the line a photo has in the file
         * @param entry the photo
         * @param root the backup folder
         *
         * Return: its size and its path inside the backup folder, separated by a tab.
         */
        fun lineOf(entry: RemoteEntry, root: String) = "${entry.size}\t${CleanupRules.relative(entry.path, root)}"
    }
}

/**
 * The cleanup's bin: a hidden folder inside the backup folder. Discarded photos are moved there by a rename, which
 * is instant and copies nothing, keeping their place inside the folder so they can be put back exactly where they were.
 */
object CleanupTrash {

    const val DIR = ".smbcommander-cestino"

    /**
     * discard() - moves a photo into the bin, keeping its place inside the backup folder
     * @param fs the file system
     * @param entry the photo
     * @param root the backup folder
     *
     * Return: where it went.
     */
    fun discard(fs: NasFileSystem, entry: RemoteEntry, root: String): String {
        val target = RemotePath.child(RemotePath.child(RemotePath.normalize(root), DIR), CleanupRules.relative(entry.path, root))
        fs.makeDirs(entry.share, RemotePath.parent(target))
        val free = EditSync.freePath(fs, entry.share, target)
        fs.rename(entry.share, entry.path, free, replace = false)
        return free
    }

    /**
     * restore() - puts a file from the bin back where it was
     * @param fs the file system
     * @param share share of the backup folder
     * @param inTrash the file in the bin
     * @param original where it was
     *
     * Return: [original], or a path next to it if that name was taken meanwhile.
     */
    fun restore(fs: NasFileSystem, share: String, inTrash: String, original: String): String {
        fs.makeDirs(share, RemotePath.parent(original))
        val free = EditSync.freePath(fs, share, original)
        fs.rename(share, inTrash, free, replace = false)
        return free
    }

    /**
     * contents() - everything in the bin
     * @param fs the file system
     * @param share share of the backup folder
     * @param root the backup folder
     *
     * Return: the files, at every level; folders are left out.
     */
    fun contents(fs: NasFileSystem, share: String, root: String): List<RemoteEntry> {
        val dir = RemotePath.child(RemotePath.normalize(root), DIR)
        if (fs.stat(share, dir) == null) return emptyList()
        val found = ArrayList<RemoteEntry>()
        fun walk(path: String) {
            for (e in fs.unlessGone(share, path) { fs.list(share, path) }.orEmpty()) if (e.isDirectory) walk(e.path) else found += e
        }
        walk(dir)
        return found
    }

    /**
     * originalOf() - where a file of the bin came from
     * @param inTrash its path in the bin
     * @param root the backup folder
     *
     * Return: its old path.
     */
    fun originalOf(inTrash: String, root: String): String {
        val r = RemotePath.normalize(root)
        val rel = CleanupRules.relative(inTrash, RemotePath.child(r, DIR))
        return RemotePath.child(r, rel)
    }

    /**
     * empty() - deletes the bin for good, file by file
     * @param fs the file system
     * @param share share of the backup folder
     * @param root the backup folder
     *
     * A file already gone (deleted or moved from another device) is skipped.
     *
     * Return: the paths of the skipped files.
     */
    fun empty(fs: NasFileSystem, share: String, root: String): List<String> {
        val skipped = contents(fs, share, root).filterNot { delete(fs, share, it.path) }
        removeFolders(fs, share, root)
        return skipped.map { it.path }
    }

    /**
     * delete() - deletes one file of the bin for good
     * @param fs the file system
     * @param share share of the backup folder
     * @param path the file in the bin
     *
     * Return: false when it was no longer there.
     */
    fun delete(fs: NasFileSystem, share: String, path: String): Boolean = fs.unlessGone(share, path) { fs.delete(share, path) } != null

    /**
     * removeFolders() - takes away the bin's folders once no file is left in them
     * @param fs the file system
     * @param share share of the backup folder
     * @param root the backup folder
     *
     * Best effort: an empty hidden folder that the NAS does not let go yet (a file still closing) does no harm and
     * goes the next time.
     *
     * Return: nothing.
     */
    fun removeFolders(fs: NasFileSystem, share: String, root: String) {
        val dir = RemotePath.child(RemotePath.normalize(root), DIR)
        runCatching { if (fs.stat(share, dir) != null && contents(fs, share, root).isEmpty()) fs.delete(share, dir) }
    }
}
