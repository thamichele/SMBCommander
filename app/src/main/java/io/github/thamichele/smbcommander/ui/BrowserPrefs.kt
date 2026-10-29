package io.github.thamichele.smbcommander.ui

import android.content.Context

/** How folders are shown, remembered between app starts. */
class BrowserPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE)

    var sort: SortMode
        get() = runCatching { SortMode.valueOf(prefs.getString("sort", null) ?: "") }.getOrDefault(SortMode.Name)
        set(value) = prefs.edit().putString("sort", value.name).apply()

    var grid: Boolean
        get() = prefs.getBoolean("grid", false)
        set(value) = prefs.edit().putBoolean("grid", value).apply()

    var showHidden: Boolean
        get() = prefs.getBoolean("show_hidden", false)
        set(value) = prefs.edit().putBoolean("show_hidden", value).apply()
}
