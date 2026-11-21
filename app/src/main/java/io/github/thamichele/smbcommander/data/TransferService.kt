package io.github.thamichele.smbcommander.data

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.thamichele.smbcommander.MainActivity
import io.github.thamichele.smbcommander.R
import io.github.thamichele.smbcommander.SmbCommanderApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while the transfer queue has work, also with the app closed or the screen off: a notification
 * with the progress, Pausa and Annulla. It holds the CPU and the Wi-Fi awake only while a transfer runs, and stops
 * itself when the queue is empty or paused.
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    private val queue get() = (application as SmbCommanderApp).transfers

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // promised to Android when the service was started: foreground first, whatever comes next. Android 12 and
        // later refuse it to an app started in the background: the queue then goes on only while the app is open,
        // instead of the whole app closing on the refusal
        if (runCatching { goForeground(build(queue.jobs.value, queue.paused.value)) }.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_PAUSE -> queue.pause()
            ACTION_RESUME -> queue.resume()
            ACTION_CANCEL -> queue.cancelAll()
        }
        if (!started) {
            started = true
            acquireLocks()
            scope.launch {
                combine(queue.jobs, queue.paused) { jobs, paused -> jobs to paused }.collectLatest { (jobs, paused) ->
                    if (jobs.none { it.busy }) {
                        stop()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(jobs, paused))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 ends data transfers in the background after hours: the queue is paused, ready to resume. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        queue.pause()
        stop()
    }

    override fun onDestroy() {
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    private fun stop() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SMBCommander:transfers")
            .apply { setReferenceCounted(false); acquire(6 * 60 * 60_000L) }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SMBCommander:transfers")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun build(jobs: List<TransferJob>, paused: Boolean): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Trasferimenti", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Copie, spostamenti e caricamenti in corso"
                }
            )
        }
        val current = jobs.firstOrNull { it.state == TransferJob.State.Running || it.state == TransferJob.State.Retrying }
        val waiting = jobs.count { it.state == TransferJob.State.Waiting }
        val title = when {
            paused -> "Trasferimenti in pausa"
            current?.state == TransferJob.State.Retrying -> "Connessione persa, riprovo tra ${current.retryIn} s"
            current != null -> current.label
            else -> "Trasferimenti in coda"
        }
        val text = listOfNotNull(current?.current?.takeIf { it.isNotEmpty() }, if (waiting > 0) "$waiting in coda" else null).joinToString(" · ")
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0, MainActivity.open(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val fraction = current?.fraction
        if (current?.state == TransferJob.State.Running) {
            if (fraction != null) builder.setProgress(1000, (fraction * 1000).toInt(), false) else builder.setProgress(0, 0, true)
        }
        if (paused) builder.addAction(0, "Riprendi", action(ACTION_RESUME)) else builder.addAction(0, "Pausa", action(ACTION_PAUSE))
        builder.addAction(0, "Annulla", action(ACTION_CANCEL))
        return builder.build()
    }

    private fun action(name: String): PendingIntent = PendingIntent.getService(
        this, name.hashCode(), Intent(this, TransferService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 44
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_CANCEL = "cancel"

        /** Called when the queue gets work; Android may refuse it from the background, and then the app carries on as before. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java)) }
        }
    }
}
