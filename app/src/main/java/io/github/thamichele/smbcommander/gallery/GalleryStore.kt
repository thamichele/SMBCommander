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
            RemoteEntry(folder.share, row.getString(0), false, row.getLong(1), row.getLong(2))
        }
    }.getOrDefault(emptyList())

    fun save(folder: SavedFolder, media: List<RemoteEntry>) {
        runCatching {
            dir.mkdirs()
            val rows = JSONArray()
            for (e in media) rows.put(JSONArray().put(e.path).put(e.size).put(e.modified))
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
}
