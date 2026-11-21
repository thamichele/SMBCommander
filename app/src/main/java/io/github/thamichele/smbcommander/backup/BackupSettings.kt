/*
 * BackupSettings.kt - the photo backup settings and the outcome of its last run, kept on the phone
 *
 * Reads and writes SharedPreferences only; scheduling the backup is BackupScheduler's job.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.backup

import android.content.Context
import io.github.thamichele.smbcommander.data.SavedFolder

data class BackupSettings(
    val enabled: Boolean = false,
    /** Where photos go on the NAS; null until the user picks a folder. */
    val destination: SavedFolder? = null,
    /** MediaStore bucket ids of the albums to save; empty = the camera album, once detected. */
    val albums: Set<String> = emptySet(),
    val includeVideos: Boolean = false,
    /** Sort into "2026/10" subfolders by the date the photo was taken. */
    val byMonth: Boolean = true,
    val chargingOnly: Boolean = false,
    /** A notification with what is left to save, the last outcome and any error. */
    val statusNotification: Boolean = true,
)

/** Outcome of the last run, shown on the backup screen. */
data class BackupStatus(
    val lastRun: Long = 0,
    val lastUploaded: Int = 0,
    val totalUploaded: Int = 0,
    val lastError: String? = null,
    /** Photos still to save after the last run; -1 when it could not tell. */
    val pending: Int = -1,
)

class BackupSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    /**
     * load() - reads the backup settings
     *
     * Return: them; the defaults when nothing is saved.
     */
    fun load(): BackupSettings {
        val share = prefs.getString("dest_share", null)
        return BackupSettings(
            enabled = prefs.getBoolean("enabled", false),
            destination = share?.let { SavedFolder(it, prefs.getString("dest_path", "").orEmpty()) },
            albums = prefs.getStringSet("albums", emptySet()).orEmpty().toSet(),
            includeVideos = prefs.getBoolean("videos", false),
            byMonth = prefs.getBoolean("by_month", true),
            chargingOnly = prefs.getBoolean("charging_only", false),
            statusNotification = prefs.getBoolean("status_notification", true),
        )
    }

    /**
     * save() - keeps the backup settings
     * @param settings the settings
     *
     * Return: nothing.
     */
    fun save(settings: BackupSettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("dest_share", settings.destination?.share)
            .putString("dest_path", settings.destination?.path)
            .putStringSet("albums", settings.albums)
            .putBoolean("videos", settings.includeVideos)
            .putBoolean("by_month", settings.byMonth)
            .putBoolean("charging_only", settings.chargingOnly)
            .putBoolean("status_notification", settings.statusNotification)
            .apply()
    }

    /**
     * status() - how the last runs went
     *
     * Return: the outcome of the last run and the totals.
     */
    fun status(): BackupStatus = BackupStatus(
        lastRun = prefs.getLong("last_run", 0),
        lastUploaded = prefs.getInt("last_uploaded", 0),
        totalUploaded = prefs.getInt("total_uploaded", 0),
        lastError = prefs.getString("last_error", null),
        pending = prefs.getInt("pending", -1),
    )

    /**
     * recordRun() - remembers how a run went
     * @param uploaded photos saved in this run
     * @param error the last error, or null
     * @param pending photos still to save
     *
     * Return: nothing.
     */
    fun recordRun(uploaded: Int, error: String?, pending: Int) {
        prefs.edit()
            .putInt("pending", pending)
            .putLong("last_run", System.currentTimeMillis())
            .putInt("last_uploaded", uploaded)
            .putInt("total_uploaded", prefs.getInt("total_uploaded", 0) + uploaded)
            .putString("last_error", error)
            .apply()
    }

    /**
     * clear() - forgets the settings and the outcomes
     *
     * Return: nothing.
     */
    fun clear() {
        prefs.edit().clear().apply()
    }
}
