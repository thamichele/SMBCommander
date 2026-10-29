package io.github.thamichele.smbcommander.data

import android.content.Context
import io.github.thamichele.smbcommander.core.EditSession
import org.json.JSONObject
import java.io.File

/** Files currently opened for editing, kept across app restarts (the editor may outlive our process). */
class EditSessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("edit_sessions", Context.MODE_PRIVATE)

    fun all(): List<EditSession> = prefs.all.values.mapNotNull { value ->
        runCatching {
            val o = JSONObject(value as String)
            EditSession(
                id = o.getString("id"),
                share = o.getString("share"),
                path = o.getString("path"),
                localFile = File(o.getString("localFile")),
                baseHash = o.getString("baseHash"),
                remoteSize = o.getLong("remoteSize"),
                remoteModified = o.getLong("remoteModified"),
                openedAt = o.getLong("openedAt"),
            )
        }.getOrNull()
    }

    fun put(session: EditSession) {
        val o = JSONObject()
            .put("id", session.id)
            .put("share", session.share)
            .put("path", session.path)
            .put("localFile", session.localFile.absolutePath)
            .put("baseHash", session.baseHash)
            .put("remoteSize", session.remoteSize)
            .put("remoteModified", session.remoteModified)
            .put("openedAt", session.openedAt)
        prefs.edit().putString(session.id, o.toString()).apply()
    }

    /** Forgets the session and deletes its local copy. */
    fun remove(session: EditSession) {
        prefs.edit().remove(session.id).apply()
        session.localFile.parentFile?.deleteRecursively()
    }
}
