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
)

/** Outcome of the last run, shown on the backup screen. */
data class BackupStatus(
    val lastRun: Long = 0,
    val lastUploaded: Int = 0,
    val totalUploaded: Int = 0,
    val lastError: String? = null,
)

class BackupSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)

    fun load(): BackupSettings {
        val share = prefs.getString("dest_share", null)
        return BackupSettings(
            enabled = prefs.getBoolean("enabled", false),
            destination = share?.let { SavedFolder(it, prefs.getString("dest_path", "").orEmpty()) },
            albums = prefs.getStringSet("albums", emptySet()).orEmpty().toSet(),
            includeVideos = prefs.getBoolean("videos", false),
            byMonth = prefs.getBoolean("by_month", true),
            chargingOnly = prefs.getBoolean("charging_only", false),
        )
    }

    fun save(settings: BackupSettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("dest_share", settings.destination?.share)
            .putString("dest_path", settings.destination?.path)
            .putStringSet("albums", settings.albums)
            .putBoolean("videos", settings.includeVideos)
            .putBoolean("by_month", settings.byMonth)
            .putBoolean("charging_only", settings.chargingOnly)
            .apply()
    }

    fun status(): BackupStatus = BackupStatus(
        lastRun = prefs.getLong("last_run", 0),
        lastUploaded = prefs.getInt("last_uploaded", 0),
        totalUploaded = prefs.getInt("total_uploaded", 0),
        lastError = prefs.getString("last_error", null),
    )

    fun recordRun(uploaded: Int, error: String?) {
        prefs.edit()
            .putLong("last_run", System.currentTimeMillis())
            .putInt("last_uploaded", uploaded)
            .putInt("total_uploaded", prefs.getInt("total_uploaded", 0) + uploaded)
            .putString("last_error", error)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
