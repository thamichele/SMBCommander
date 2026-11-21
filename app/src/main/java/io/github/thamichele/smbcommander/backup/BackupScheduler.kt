/*
 * BackupScheduler.kt - when the photo backup runs: hourly on Wi-Fi, or right now
 *
 * Turns the backup settings into WorkManager work, and cancels it when the backup is off. The backup itself is
 * PhotoBackupWorker.
 *
 * Version: 1.28.6
 */
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

    /**
     * apply() - schedules or cancels the backup as the settings say
     * @param context any context of the app
     * @param settings the backup settings
     *
     * Hourly check while enabled, only on Wi-Fi (the NAS is reachable at home only) and with enough battery.
     *
     * Return: nothing.
     */
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

    /**
     * runNow() - a run right away, still waiting for Wi-Fi
     * @param context any context of the app
     * @param settings the backup settings
     * @param respectCharging also wait for the charger when the settings ask for it
     *
     * From the "Esegui ora" button or when the app opens.
     *
     * Return: nothing.
     */
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
