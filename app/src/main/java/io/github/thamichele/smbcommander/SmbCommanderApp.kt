package io.github.thamichele.smbcommander

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import io.github.thamichele.smbcommander.backup.BackupLedger
import io.github.thamichele.smbcommander.backup.BackupScheduler
import io.github.thamichele.smbcommander.backup.BackupSettingsStore
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.data.CredentialStore
import io.github.thamichele.smbcommander.data.EditSessionStore
import io.github.thamichele.smbcommander.data.FolderStore
import io.github.thamichele.smbcommander.data.NasRepository
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.data.ThumbnailCache
import io.github.thamichele.smbcommander.data.TransferQueue
import io.github.thamichele.smbcommander.gallery.GalleryPrefs
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File

class SmbCommanderApp : Application(), SingletonImageLoader.Factory {

    lateinit var repository: NasRepository
        private set
    lateinit var editSessions: EditSessionStore
        private set
    lateinit var thumbnails: ThumbnailCache
        private set
    lateinit var folders: FolderStore
        private set
    lateinit var backupSettings: BackupSettingsStore
        private set
    lateinit var backupLedger: BackupLedger
        private set
    lateinit var galleryPrefs: GalleryPrefs
        private set
    lateinit var transfers: TransferQueue
        private set

    /** Photos just deleted from the NAS, so every screen that shows them can drop them. */
    val deletedPhotos = MutableSharedFlow<List<RemoteEntry>>(extraBufferCapacity = 16)

    /** Something was written to the NAS (a folder, or null when it could be anywhere): the gallery looks again. */
    val nasChanged = MutableSharedFlow<NasChange?>(extraBufferCapacity = 16)

    override fun onCreate() {
        super.onCreate()
        repository = NasRepository(CredentialStore(this))
        editSessions = EditSessionStore(this)
        folders = FolderStore(this)
        backupSettings = BackupSettingsStore(this)
        backupLedger = BackupLedger(File(filesDir, "backup-ledger.txt"))
        galleryPrefs = GalleryPrefs(this)
        transfers = TransferQueue(repository)
        // catch up on photos taken while the app was closed (still waits for Wi-Fi)
        BackupScheduler.runNow(this, backupSettings.load(), respectCharging = true)
        thumbnails = ThumbnailCache(File(cacheDir, "images"), repository)
        Thread { runCatching { thumbnails.trimPreviews() } }.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(ThumbnailCache.Fetcher.Factory(thumbnails)) }
            .crossfade(true)
            .build()
}

/** A folder of the NAS whose content just changed. */
data class NasChange(val share: String, val path: String) {
    /** True when the change is inside [folder], or moved or replaced a folder that contains it. */
    fun affects(folder: SavedFolder): Boolean {
        if (share != folder.share) return false
        val changed = RemotePath.normalize(path)
        val shown = RemotePath.normalize(folder.path)
        return changed == shown || shown.isEmpty() || changed.isEmpty() ||
            changed.startsWith("$shown/") || shown.startsWith("$changed/")
    }
}
