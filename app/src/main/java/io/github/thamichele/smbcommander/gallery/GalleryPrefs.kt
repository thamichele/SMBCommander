package io.github.thamichele.smbcommander.gallery

import android.content.Context

/** How the gallery looks and behaves, remembered on the phone. */
class GalleryPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    var view: GalleryView
        get() = prefs.getString("view", null)?.let { name -> GalleryView.entries.firstOrNull { it.name == name } }
            // before the mosaic there were only the grid and the list
            ?: if (prefs.getBoolean("list_view", false)) GalleryView.List else GalleryView.Grid
        set(value) = prefs.edit().putString("view", value.name).apply()

    var deleteWithoutConfirm: Boolean
        get() = prefs.getBoolean("delete_without_confirm", false)
        set(value) = prefs.edit().putBoolean("delete_without_confirm", value).apply()
}
