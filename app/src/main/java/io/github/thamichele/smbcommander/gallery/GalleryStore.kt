/*
 * GalleryStore.kt - the gallery's last known content, kept on the phone between two runs of the app
 *
 * One small JSON file per backup folder: opening the gallery shows it at once, and a refresh only corrects what
 * changed on the NAS since. A file that cannot be read counts as empty; walking the NAS is GalleryViewModel's job.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.gallery

import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.data.cacheKey
import org.json.JSONArray
import java.io.File

/** The content of each backup folder as the gallery last saw it, in [dir]. */
class GalleryStore(private val dir: File) {

    /**
     * load() - what the gallery knew of a backup folder
     * @param folder the backup folder
     *
     * Context: blocking, reads a file of the phone.
     * Return: the media and the empty albums; empty when nothing was saved or the file is damaged.
     */
    fun load(folder: SavedFolder): List<RemoteEntry> = runCatching {
        val file = file(folder)
        if (!file.exists()) return emptyList()
        val rows = JSONArray(file.readText())
        List(rows.length()) { i ->
            val row = rows.getJSONArray(i)
            // an empty album is kept as a folder with a size no file can have
            val isDirectory = row.getLong(1) == DIRECTORY_SIZE
            RemoteEntry(folder.share, row.getString(0), isDirectory, if (isDirectory) 0 else row.getLong(1), row.getLong(2))
        }
    }.getOrDefault(emptyList())

    /**
     * save() - keeps what the gallery knows of a backup folder
     * @param folder the backup folder
     * @param media the media and the empty albums, as GalleryIndex.stored gives them
     *
     * Best effort: a file that cannot be written only costs a longer refresh at the next start.
     *
     * Context: blocking, writes a file of the phone.
     * Return: nothing.
     */
    fun save(folder: SavedFolder, media: List<RemoteEntry>) {
        runCatching {
            dir.mkdirs()
            val rows = JSONArray()
            for (e in media) rows.put(JSONArray().put(e.path).put(if (e.isDirectory) DIRECTORY_SIZE else e.size).put(e.modified))
            val target = file(folder)
            val temp = File(dir, target.name + ".part")
            temp.writeText(rows.toString())
            temp.renameTo(target)
        }
    }

    private fun file(folder: SavedFolder): File {
        return File(dir, cacheKey(folder.fullPath) + ".json")
    }

    private companion object {
        /** Written in place of the size for a folder: the empty albums, see GalleryIndex.stored. */
        const val DIRECTORY_SIZE = -1L
    }
}
