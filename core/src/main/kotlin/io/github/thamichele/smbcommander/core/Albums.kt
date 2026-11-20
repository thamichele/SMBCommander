/*
 * Albums.kt - the albums of the gallery: one per folder below the backup folder
 *
 * Works out which folders are albums, how they are named and ordered, and where a new album goes. Pure logic on
 * what the gallery already knows: it never talks to the NAS and never renames or rearranges a folder. Moving the
 * photos is the job of Transfers, listing the folders that of GalleryIndex.
 *
 * Version: 1.20.0
 */
package io.github.thamichele.smbcommander.core

/** A folder below the backup folder, shown as an album with the photos and videos directly inside it. */
data class Album(
    /** Path of the folder inside the share. */
    val path: String,
    /** Path below the backup folder, such as "Vacanze" or "2026/10"; empty for the backup folder itself. */
    val name: String,
    /** Photos and videos directly in the folder. */
    val count: Int,
    /** The newest photo of the album; null while the album is empty. */
    val cover: RemoteEntry?,
    /** The folder names a month, as those the backup creates: listed apart, newest first. */
    val isDated: Boolean,
)

object Albums {

    /**
     * list() - the albums of a backup folder, in the order they are shown
     * @param root the backup folder, as a normalized path inside the share
     * @param folders the photos and videos of each folder that has any, by folder path
     * @param empty folders with nothing inside: albums just created, still to be filled
     * @param sortKey when a photo was taken, bigger is newer; picks the cover
     *
     * A folder is an album when it holds photos directly, or when it is empty. A folder that only holds other
     * folders ("2026" over its months) is not. Albums with a name of their own come first, by name; those named
     * after a month follow, newest first, so years of backup do not bury the others.
     *
     * Context: pure, no I/O.
     * Return: the albums, never null; empty when the backup folder has neither photos nor empty folders.
     */
    fun list(
        root: String,
        folders: Map<String, List<RemoteEntry>>,
        empty: Collection<String>,
        sortKey: (RemoteEntry) -> Long,
    ): List<Album> {
        val filled = folders.filterValues { it.isNotEmpty() }.map { (dir, media) ->
            Album(dir, nameOf(root, dir), media.size, media.maxByOrNull(sortKey), isDated(root, dir))
        }
        val unfilled = empty.filter { it !in folders }.map { Album(it, nameOf(root, it), 0, null, isDated(root, it)) }
        val (dated, named) = (filled + unfilled).partition { it.isDated }
        return named.sortedBy { it.name.lowercase() } + dated.sortedByDescending { it.name }
    }

    /**
     * nameOf() - the name of an album: the path of its folder below the backup folder
     * @param root the backup folder, normalized
     * @param dir the folder of the album, normalized
     *
     * Return: "Vacanze", "2026/10"; "" for the backup folder itself, and [dir] as it is when it is not below [root].
     */
    fun nameOf(root: String, dir: String): String = when {
        dir == root -> ""
        root.isEmpty() -> dir
        dir.startsWith("$root/") -> dir.substring(root.length + 1)
        else -> dir
    }

    /**
     * newPath() - where the folder of a new album goes: directly in the backup folder
     * @param root the backup folder
     * @param name the name typed by the user, spaces around it ignored
     *
     * Hidden names are refused too: the gallery does not show folders starting with a dot, an "@" or a "#" (the
     * NAS's own), so such an album would vanish right after being created.
     *
     * Return: the normalized path of the new folder.
     * @throws IllegalArgumentException when [name] cannot be a folder name, with the reason to show
     */
    fun newPath(root: String, name: String): String {
        val clean = name.trim()
        require(RemotePath.isValidName(clean)) { "Nome non valido: non può contenere \\ / : * ? \" < > |" }
        require(clean.first() !in HIDDEN_PREFIXES) { "Il nome di un album non può iniziare con «${clean.first()}»" }
        return RemotePath.child(root, clean)
    }

    /**
     * holds() - whether a photo lies directly in an album
     * @param album the folder of the album, normalized
     * @param path the path of the photo, normalized (as every path the gallery lists)
     *
     * Return: true when the folder of [path] is [album]; photos in its subfolders belong to other albums.
     */
    fun holds(album: String, path: String): Boolean = path.substringBeforeLast('/', "") == album

    private fun isDated(root: String, dir: String): Boolean {
        val name = nameOf(root, dir)
        return name.isNotEmpty() && GalleryDates.namesMonth(name.split('/'))
    }

    /** First characters of the names the gallery hides, see GalleryViewModel. */
    private const val HIDDEN_PREFIXES = ".@#~"
}
