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
 * the same, only the main colour changes.
 *
 * [main] is the colour of the theme (switches, progress, selection), [button] fills primary buttons with [onButton]
 * text on it, [hover] is the pressed shade, [text] is for links and labels on the dark ground, [soft] for tags,
 * [border] and [deep] for tinted outlines and grounds, [icon] for icons on the dark ground (launcher icon, widget),
 * [light] replaces all of them on light grounds (white text on it). Viola and Rosso use white text on their buttons,
 * Rosso fills buttons with a darker red because pure red reads badly with any text colour.
 */
enum class AccentTheme(
    val label: String,
    val main: Color,
    val hover: Color,
    val text: Color,
    val soft: Color,
    val border: Color,
    val deep: Color,
    val light: Color,
    val button: Color = main,
    val onButton: Color = Color(0xFF1C1B1A),
    val icon: Color = main,
) {
    Arancio("Arancio", Color(0xFFFE4C10), Color(0xFFFF5F26), Color(0xFFFF6A33), Color(0xFFFF8A5C), Color(0xFF73260B), Color(0xFF3A1C10), Color(0xFFC93A06)),
    Viola(
        "Viola", Color(0xFF6F00FF), Color(0xFF7F1AFF), Color(0xFFA875FF), Color(0xFFBC96FF), Color(0xFF3A1F6E), Color(0xFF1E1238), Color(0xFF6F00FF),
        onButton = Color.White, icon = Color(0xFFA875FF),
    ),
    Rosso(
        "Rosso", Color(0xFFF80000), Color(0xFFFF1A1A), Color(0xFFFF4D4D), Color(0xFFFF7A7A), Color(0xFF6E1414), Color(0xFF3A1010), Color(0xFFC80000),
        button = Color(0xFFD90000), onButton = Color.White,
    ),
    Giallo("Giallo", Color(0xFFF5B800), Color(0xFFFFC61F), Color(0xFFFFCB33), Color(0xFFFFD966), Color(0xFF6E5508), Color(0xFF33290A), Color(0xFF8A6A00)),
    Blu("Blu", Color(0xFF3D8BFF), Color(0xFF5297FF), Color(0xFF66A3FF), Color(0xFF8CBAFF), Color(0xFF1F3F70), Color(0xFF14223A), Color(0xFF1F5FCC)),
    Verde("Verde", Color(0xFF1FC46E), Color(0xFF2ED07B), Color(0xFF3DD687), Color(0xFF6FE0A5), Color(0xFF175C38), Color(0xFF12291D), Color(0xFF12804A)),
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
