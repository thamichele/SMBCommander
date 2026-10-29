package io.github.thamichele.smbcommander.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.thamichele.smbcommander.R
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.BackupPlacement
import io.github.thamichele.smbcommander.core.EditSync
import io.github.thamichele.smbcommander.core.NasException
import io.github.thamichele.smbcommander.core.NasFileSystem
import io.github.thamichele.smbcommander.core.RemotePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Copies new photos (and videos, if chosen) from the selected albums to the NAS. Never deletes anything on the phone.
 * Each file goes up under a temporary name and is renamed when complete; the ledger remembers what is done, so a run
 * cut short by Android or by leaving home picks up where it stopped.
 */
class PhotoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val app = context.applicationContext as SmbCommanderApp

    override suspend fun doWork(): Result = runLock.withLock { withContext(Dispatchers.IO) { backup() } }

    private suspend fun backup(): Result {
        val settings = app.backupSettings.load()
        val destination = settings.destination
        if (!settings.enabled || destination == null) return Result.success()
        if (!hasMediaPermission(applicationContext)) {
            app.backupSettings.recordRun(0, "Manca il permesso di leggere le foto")
            return Result.success()
        }
        if (!app.repository.isConfigured) return Result.success()

        val pending = MediaScanner(applicationContext).items(settings.albums, settings.includeVideos)
            .filterNot { app.backupLedger.contains(it.key) }
        if (pending.isEmpty()) {
            app.backupSettings.recordRun(0, null)
            return Result.success()
        }

        // a visible notification keeps long first backups alive; Android may refuse it when we start in background
        runCatching { setForeground(foregroundInfo(0, pending.size)) }

        val fs = try {
            app.repository.fs().also { it.list(destination.share, destination.path) }
        } catch (e: Exception) {
            // most likely away from home: try again at the next run
            app.backupSettings.recordRun(0, "NAS non raggiungibile: ${e.message ?: e.javaClass.simpleName}")
            return Result.retry()
        }

        val createdDirs = HashSet<String>()
        var uploaded = 0
        var lastError: String? = null
        for ((i, item) in pending.withIndex()) {
            if (isStopped) break
            setProgress(workDataOf(PROGRESS_DONE to i, PROGRESS_TOTAL to pending.size))
            if (i % 5 == 0) runCatching { setForeground(foregroundInfo(i, pending.size)) }
            try {
                upload(fs, destination.share, folderFor(destination.path, item, settings.byMonth), item, createdDirs)
                app.backupLedger.add(item.key)
                uploaded++
            } catch (e: NasException) {
                app.backupSettings.recordRun(uploaded, "Backup interrotto: ${e.message}")
                return Result.retry()
            } catch (e: Exception) {
                // one unreadable or rejected file must not block all the others
                lastError = "«${item.name}» non salvato: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        app.backupSettings.recordRun(uploaded, lastError)
        return Result.success()
    }

    private fun upload(fs: NasFileSystem, share: String, folder: String, item: MediaItem, createdDirs: MutableSet<String>) {
        ensureFolder(fs, share, folder, createdDirs)
        // the name comes from whichever app saved the photo: never let it pick another folder
        val name = item.name.takeIf { RemotePath.isValidName(it) } ?: "media_${item.key.substringAfter(':').substringBefore(':')}"
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasLocationPermission()) {
            // keeps the GPS position in the photo, which Android strips otherwise
            MediaStore.setRequireOriginal(item.uri)
        } else item.uri
        val open = {
            applicationContext.contentResolver.openInputStream(uri) ?: throw IllegalStateException("file non leggibile")
        }
        // after a reinstall the ledger is empty: photos already on the NAS are recognised by size and hash
        val placement = BackupPlacement.find(fs, share, RemotePath.child(folder, name), item.size) {
            open().use { BackupPlacement.sha256(it) }
        }
        if (placement is BackupPlacement.Upload) EditSync.safeUpload(fs, share, placement.path, item.size, open)
    }

    private fun ensureFolder(fs: NasFileSystem, share: String, folder: String, createdDirs: MutableSet<String>) {
        var current = ""
        for (part in folder.split('/').filter { it.isNotEmpty() }) {
            current = RemotePath.child(current, part)
            if (current in createdDirs) continue
            if (fs.stat(share, current) == null) fs.createDirectory(share, current)
            createdDirs += current
        }
    }

    private fun folderFor(base: String, item: MediaItem, byMonth: Boolean): String {
        if (!byMonth) return base
        val cal = Calendar.getInstance().apply { timeInMillis = item.taken }
        val month = "%02d".format(cal.get(Calendar.MONTH) + 1)
        return RemotePath.child(RemotePath.child(base, cal.get(Calendar.YEAR).toString()), month)
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Backup foto", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Backup foto sul NAS")
            .setContentText(if (total > 0) "$done di $total" else "Preparazione")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
        private const val CHANNEL = "backup"
        private const val NOTIFICATION_ID = 42

        /** The periodic run and a manual "run now" must never upload the same photo twice. */
        private val runLock = Mutex()

        fun mediaPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        fun hasMediaPermission(context: Context): Boolean {
            val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_IMAGES
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
            return ContextCompat.checkSelfPermission(context, needed) == PackageManager.PERMISSION_GRANTED
        }
    }
}
