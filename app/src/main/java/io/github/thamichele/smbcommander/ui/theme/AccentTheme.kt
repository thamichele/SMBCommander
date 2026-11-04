package io.github.thamichele.smbcommander.ui.theme

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The main colours of the "Modalità colori" board of the Arancio UI design: greys, layout and status colours stay
 * the same, only the main colour changes. [main] fills buttons and switches on the dark ground (dark text on it),
 * [text] is for links and labels on the dark ground, [light] replaces [main] on light grounds (white text on it).
 */
enum class AccentTheme(val label: String, val main: Color, val text: Color, val light: Color) {
    Arancio("Arancio", Color(0xFFD96D00), Color(0xFFF08A2A), Color(0xFFA85400)),
    Viola("Viola", Color(0xFF9D6BFF), Color(0xFFB48CFF), Color(0xFF6D3FD9)),
    Rosso("Rosso", Color(0xFFEC5A5A), Color(0xFFF27C7C), Color(0xFFC42B2B)),
    Giallo("Giallo", Color(0xFFE5B000), Color(0xFFF2C230), Color(0xFF8A6A00)),
    Blu("Blu", Color(0xFF4A90FF), Color(0xFF6BA6FF), Color(0xFF1F5FCC)),
    Verde("Verde", Color(0xFF2FB36B), Color(0xFF4FCB87), Color(0xFF1A7F48)),
}

/** The chosen main colour, remembered on the phone and shared by every screen of the app. */
object AccentStore {

    private const val PREFS = "appearance"
    private const val KEY = "accent"

    private val current = MutableStateFlow(AccentTheme.Arancio)
    @Volatile private var loaded = false

    fun state(context: Context): StateFlow<AccentTheme> {
        load(context)
        return current.asStateFlow()
    }

    fun get(context: Context): AccentTheme {
        load(context)
        return current.value
    }

    fun set(context: Context, theme: AccentTheme) {
        load(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.name).apply()
        current.value = theme
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        val name = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        current.value = AccentTheme.entries.firstOrNull { it.name == name } ?: AccentTheme.Arancio
        loaded = true
    }
}

/**
 * Shows the launcher icon of the chosen colour: one activity-alias per colour, only one of them enabled.
 * Called when the app goes to the background, because some launchers close an app whose entry changes
 * while it is open.
 */
object LauncherIcon {

    fun sync(context: Context) {
        val wanted = AccentStore.get(context)
        val pm = context.packageManager
        // the new entry first, so there is never a moment without any launcher icon
        set(pm, context, wanted, true)
        AccentTheme.entries.filter { it != wanted }.forEach { set(pm, context, it, false) }
    }

    private fun set(pm: PackageManager, context: Context, theme: AccentTheme, enabled: Boolean) {
        val component = ComponentName(context.packageName, "io.github.thamichele.smbcommander.Launcher${theme.name}")
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if (pm.getComponentEnabledSetting(component) == state) return
        pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }
}
