package io.github.thamichele.smbcommander.gallery

import android.content.Context

/** How the gallery looks and behaves, remembered on the phone. */
class GalleryPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    var listView: Boolean
        get() = prefs.getBoolean("list_view", false)
        set(value) = prefs.edit().putBoolean("list_view", value).apply()

    var deleteWithoutConfirm: Boolean
        get() = prefs.getBoolean("delete_without_confirm", false)
        set(value) = prefs.edit().putBoolean("delete_without_confirm", value).apply()
}
