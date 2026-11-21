/*
 * WidgetConfigActivity.kt - picks the folder a widget opens, among the user's folders
 *
 * Shown by Android when the widget is placed.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.AppLock
import io.github.thamichele.smbcommander.ui.PageHeader
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioTheme

/**
 * Picks the folder a widget opens, among "Le mie cartelle". The launcher starts it, so it is open to other apps too:
 * it only answers for a widget of this app, and with the app lock on only to the owner.
 */
class WidgetConfigActivity : FragmentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // backing out leaves no half-made widget behind
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (!isOurWidget(widgetId)) {
            finish()
            return
        }
        AppLock.guard(this, (application as SmbCommanderApp).extras, ::show)
    }

    /** True only for a widget of SMBCommander already placed on the home screen. */
    private fun isOurWidget(id: Int): Boolean {
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return false
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id) ?: return false
        return info.provider == ComponentName(this, BackupWidget::class.java)
    }

    private fun show() {
        val folders = (application as SmbCommanderApp).folders.all()
        setContent {
            ArancioTheme {
                val c = Arancio.colors
                Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()) {
                    PageHeader(context = "Widget", title = "Quale cartella apre?")
                    LazyColumn {
                        items(folders, key = { it.fullPath }) { folder -> Choice(folder.name, folder.shown) { choose(folder) } }
                        item { Choice("Nessuna", "Apre SMBCommander") { choose(null) } }
                    }
                }
            }
        }
    }

    private fun choose(folder: SavedFolder?) {
        WidgetPrefs(this).set(widgetId, folder)
        BackupWidget.update(this, AppWidgetManager.getInstance(this), widgetId)
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@androidx.compose.runtime.Composable
private fun Choice(title: String, detail: String, onClick: () -> Unit) {
    val c = Arancio.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Folder, contentDescription = null, tint = c.folder, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}
