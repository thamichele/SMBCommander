/*
 * FolderStore.kt - the folders the user added to the app, kept on the phone
 *
 * The start screen shows only these: nothing of the NAS appears on its own. No NAS access.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.data

import android.content.Context
import io.github.thamichele.smbcommander.core.NasShares
import io.github.thamichele.smbcommander.core.RemotePath
import org.json.JSONArray
import org.json.JSONObject

/** A folder the user chose to manage from the app: a whole share or a folder inside one. */
data class SavedFolder(val share: String, val path: String) {
    val name: String get() = if (path.isEmpty()) NasShares.shownName(share) else RemotePath.name(path)
    /** Unique key of the folder; for another NAS it holds its internal "nas<id>:" name, see [shown]. */
    val fullPath: String get() = if (path.isEmpty()) share else "$share/$path"
    /** Where the folder is, as the user reads it: "Multimedia / Foto", or "Ufficio / Musica" on another NAS. */
    val shown: String get() = NasShares.display(share, path)
    /** The folder that stands for a whole other NAS and lists its shares. */
    val isNas: Boolean get() = NasShares.isRoot(share)
    /** On the main NAS, the only one the backup and the gallery use. */
    val onMainNas: Boolean get() = NasShares.nasOf(share) == null

    /**
     * contains() - true when [path] of [share] is this folder or inside it
     * @param share any share name used by the app
     * @param path a path inside that share
     *
     * The folder of a whole other NAS contains every share of that NAS.
     *
     * Return: true when the place is inside this folder.
     */
    fun contains(share: String, path: String): Boolean {
        if (isNas) return NasShares.nasOf(share) == NasShares.nasOf(this.share)
        if (!this.share.equals(share, ignoreCase = true)) return false
        val dir = RemotePath.normalize(this.path)
        val p = RemotePath.normalize(path)
        return dir.isEmpty() || p == dir || p.startsWith("$dir/")
    }

    /**
     * parent() - the folder one level up
     *
     * Above a share of another NAS there is the folder of that NAS, which lists its shares.
     *
     * Return: the folder above, or null at the top of a share of the main NAS or of a whole NAS.
     */
    fun parent(): SavedFolder? = when {
        path.isNotEmpty() -> SavedFolder(share, RemotePath.parent(path))
        isNas -> null
        else -> NasShares.nasOf(share)?.let { SavedFolder(NasShares.root(it), "") }
    }

    companion object {
        /**
         * parse() - reads a folder typed by the user
         * @param input "Multimedia/Foto", "\\Backup$\vecchi" or "smb://nas/Backup$"
         *
         * Return: share and path, or null when the input is empty.
         */
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

    /**
     * all() - the folders the user added
     *
     * Return: them, in the order they were added.
     */
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

    /**
     * add() - adds a folder
     * @param folder the folder
     *
     * Return: the updated list.
     */
    fun add(folder: SavedFolder): List<SavedFolder> =
        all().filterNot { it.fullPath.equals(folder.fullPath, ignoreCase = true) }.plus(folder).also(::save)

    /**
     * remove() - removes a folder from the app; nothing changes on the NAS
     * @param folder the folder
     *
     * Return: the updated list.
     */
    fun remove(folder: SavedFolder): List<SavedFolder> = all().filterNot { it == folder }.also(::save)

    /**
     * removeNas() - drops every folder of another NAS, its own folder included
     * @param nasId the NAS that was removed
     *
     * Return: the updated list.
     */
    fun removeNas(nasId: String): List<SavedFolder> = all().filterNot { NasShares.nasOf(it.share) == nasId }.also(::save)

    /**
     * clear() - forgets every folder
     *
     * Return: nothing.
     */
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
