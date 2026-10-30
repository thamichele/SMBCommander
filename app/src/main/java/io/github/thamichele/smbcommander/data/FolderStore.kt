package io.github.thamichele.smbcommander.data

import android.content.Context
import io.github.thamichele.smbcommander.core.RemotePath
import org.json.JSONArray
import org.json.JSONObject

/** A folder the user chose to manage from the app: a whole share or a folder inside one. */
data class SavedFolder(val share: String, val path: String) {
    val name: String get() = if (path.isEmpty()) share else RemotePath.name(path)
    val fullPath: String get() = if (path.isEmpty()) share else "$share/$path"

    companion object {
        /** "Multimedia/Foto", "\\Backup$\vecchi" or "smb://nas/Backup$" -> share + path; null when empty. */
        fun parse(input: String): SavedFolder? {
            var text = input.trim()
            // a full "smb://host/share/..." address: drop scheme and host
            if (text.startsWith("smb://", ignoreCase = true)) text = text.substring(6).substringAfter('/', "")
            val clean = RemotePath.normalize(text)
            if (clean.isEmpty()) return null
            return SavedFolder(clean.substringBefore('/'), clean.substringAfter('/', ""))
        }
    }
}

/** The user's own list of folders, in the order they were added. Only these show on the start screen. */
class FolderStore(context: Context) {

    private val prefs = context.getSharedPreferences("folders", Context.MODE_PRIVATE)

    fun all(): List<SavedFolder> {
        val json = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                SavedFolder(o.getString("share"), o.optString("path", ""))
            }
        }.getOrDefault(emptyList())
    }

    fun add(folder: SavedFolder): List<SavedFolder> =
        all().filterNot { it.fullPath.equals(folder.fullPath, ignoreCase = true) }.plus(folder).also(::save)

    fun remove(folder: SavedFolder): List<SavedFolder> = all().filterNot { it == folder }.also(::save)

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun save(folders: List<SavedFolder>) {
        val array = JSONArray()
        folders.forEach { array.put(JSONObject().put("share", it.share).put("path", it.path)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "folders"
    }
}
