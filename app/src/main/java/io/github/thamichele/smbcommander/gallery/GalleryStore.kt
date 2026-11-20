package io.github.thamichele.smbcommander.gallery

import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.SavedFolder
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

/**
 * The gallery's last known content, kept on the phone: opening the gallery shows it at once, and a refresh only
 * corrects what changed on the NAS since.
 */
class GalleryStore(private val dir: File) {

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
        val key = MessageDigest.getInstance("SHA-1").digest(folder.fullPath.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$key.json")
    }

    private companion object {
        /** Written in place of the size for a folder: the empty albums, see GalleryIndex.stored. */
        const val DIRECTORY_SIZE = -1L
    }
}
