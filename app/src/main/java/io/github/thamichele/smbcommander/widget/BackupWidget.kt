package io.github.thamichele.smbcommander.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import io.github.thamichele.smbcommander.MainActivity
import io.github.thamichele.smbcommander.R
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.backup.BackupScheduler
import io.github.thamichele.smbcommander.data.SavedFolder

/** Home screen widget: one of the user's folders on the left, "Backup ora" with the backup status on the right. */
class BackupWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetPrefs(context).remove(it) }
    }

    companion object {
        /** Redraws every widget, e.g. after a backup run changed the status line. */
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, BackupWidget::class.java)).forEach { update(context, manager, it) }
        }

        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val app = context.applicationContext as SmbCommanderApp
            val folder: SavedFolder? = WidgetPrefs(context).folder(id)
            val views = RemoteViews(context.packageName, R.layout.widget_backup)

            views.setTextViewText(R.id.widget_folder_name, folder?.name ?: "SMBCommander")
            val open = if (folder != null) MainActivity.openFolder(context, folder.share, folder.path)
            else context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent(context, MainActivity::class.java)
            views.setOnClickPendingIntent(
                R.id.widget_folder,
                PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )

            val status = app.backupSettings.status()
            val settings = app.backupSettings.load()
            views.setTextViewText(
                R.id.widget_backup_status,
                when {
                    !settings.enabled -> "Disattivato"
                    status.lastError != null && status.pending != 0 -> "Errore · ${status.pending.coerceAtLeast(0)} da salvare"
                    status.pending > 0 -> "${status.pending} da salvare"
                    status.pending == 0 -> "Tutto salvato"
                    else -> "In attesa"
                },
            )
            val backup = Intent(context, BackupNowReceiver::class.java)
            views.setOnClickPendingIntent(
                R.id.widget_backup,
                PendingIntent.getBroadcast(context, 0, backup, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
            manager.updateAppWidget(id, views)
        }
    }
}

/**
 * "Backup ora" of the widget. A receiver of its own, not exported: the widget provider must be reachable by the
 * launcher, and this way no other app can start a backup by sending it a broadcast.
 */
class BackupNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SmbCommanderApp
        val settings = app.backupSettings.load()
        val text = when {
            settings.destination == null -> "Scegli prima la cartella del backup nell'app"
            !settings.enabled -> "Attiva prima il backup nell'app"
            else -> {
                BackupScheduler.runNow(context, settings)
                "Backup avviato: parte appena sei sul Wi-Fi"
            }
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}

/** Which folder each widget opens. */
class WidgetPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun folder(id: Int): SavedFolder? {
        val share = prefs.getString("$id.share", null) ?: return null
        return SavedFolder(share, prefs.getString("$id.path", "").orEmpty())
    }

    fun set(id: Int, folder: SavedFolder?) {
        val edit = prefs.edit()
        if (folder == null) edit.remove("$id.share").remove("$id.path")
        else edit.putString("$id.share", folder.share).putString("$id.path", folder.path)
        edit.apply()
    }

    fun remove(id: Int) = set(id, null)
}
