package io.github.thamichele.smbcommander.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.thamichele.smbcommander.MainActivity
import io.github.thamichele.smbcommander.R
import io.github.thamichele.smbcommander.SmbCommanderApp

/**
 * The backup status notification: how many photos are still to save, how the last run went and the error, if any.
 * It shows only when there is something to say (photos waiting, an error, photos just saved) and is quiet otherwise.
 */
object BackupNotifier {

    private const val CHANNEL = "backup_status"
    private const val NOTIFICATION_ID = 43

    fun update(context: Context) {
        val app = context.applicationContext as SmbCommanderApp
        val settings = app.backupSettings.load()
        val status = app.backupSettings.status()
        val manager = context.getSystemService(NotificationManager::class.java)
        val worthShowing = status.pending > 0 || status.lastError != null || status.lastUploaded > 0
        if (!settings.enabled || !settings.statusNotification || !worthShowing || !allowed(context)) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Stato del backup", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Foto ancora da salvare, ultimo backup ed errori"
                }
            )
        }
        val title = when {
            status.pending > 0 -> if (status.pending == 1) "1 foto da salvare sul NAS" else "${status.pending} foto da salvare sul NAS"
            status.lastError != null -> "Backup foto con errori"
            else -> "Backup foto completato"
        }
        val time = DateFormat.getTimeFormat(context).format(status.lastRun)
        val last = "Ultimo backup alle $time: " + when (status.lastUploaded) {
            0 -> "nessuna foto nuova"
            1 -> "1 foto salvata"
            else -> "${status.lastUploaded} foto salvate"
        }
        val text = listOfNotNull(last, status.lastError).joinToString("\n")
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            MainActivity.openBackup(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(last)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setAutoCancel(status.pending <= 0 && status.lastError == null)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
