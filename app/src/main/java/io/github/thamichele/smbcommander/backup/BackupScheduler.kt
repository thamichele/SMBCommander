package io.github.thamichele.smbcommander.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackupScheduler {
    const val PERIODIC = "photo-backup"
    const val NOW = "photo-backup-now"

    /** Hourly check while enabled, only on Wi-Fi (the NAS is reachable at home only) and with enough battery. */
    fun apply(context: Context, settings: BackupSettings) {
        val wm = WorkManager.getInstance(context)
        if (!settings.enabled || settings.destination == null) {
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(NOW)
            return
        }
        wm.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<PhotoBackupWorker>(1, TimeUnit.HOURS)
                .setConstraints(constraints(settings))
                .build(),
        )
    }

    /** A run right away (still waiting for Wi-Fi), e.g. from the "Esegui ora" button or when the app opens. */
    fun runNow(context: Context, settings: BackupSettings, respectCharging: Boolean = false) {
        if (!settings.enabled || settings.destination == null) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOW,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<PhotoBackupWorker>()
                .setConstraints(constraints(settings.copy(chargingOnly = settings.chargingOnly && respectCharging)))
                .build(),
        )
    }

    private fun constraints(settings: BackupSettings) = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .setRequiresCharging(settings.chargingOnly)
        .build()
}
