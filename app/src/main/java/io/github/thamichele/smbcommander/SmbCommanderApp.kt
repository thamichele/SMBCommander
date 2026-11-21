/*
 * SmbCommanderApp.kt - the application object: builds the shared stores and caches once, at start
 *
 * Owns the NAS repository, the caches of thumbnails and video frames, the stores of settings and the transfer queue,
 * and the image loader Coil uses. Screens and workers reach them from here; nothing in it draws or talks to the user.
 *
 * Version: 1.28.3
 */
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
import io.github.thamichele.smbcommander.data.AspectRatios
import io.github.thamichele.smbcommander.data.CredentialStore
import io.github.thamichele.smbcommander.data.EditSessionStore
import io.github.thamichele.smbcommander.data.Extras
import io.github.thamichele.smbcommander.data.FolderStore
import io.github.thamichele.smbcommander.data.MusicController
import io.github.thamichele.smbcommander.data.NasRepository
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.data.ShareCache
import io.github.thamichele.smbcommander.data.PhotoSorter
import io.github.thamichele.smbcommander.data.ThumbnailCache
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.data.VideoFramePrefs
import io.github.thamichele.smbcommander.data.VideoFrames
import io.github.thamichele.smbcommander.data.TransferQueue
import io.github.thamichele.smbcommander.data.TransferService
import io.github.thamichele.smbcommander.ui.isConnectionLost
import io.github.thamichele.smbcommander.ui.readable
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    lateinit var photoSorter: PhotoSorter
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
    lateinit var shareCache: ShareCache
        private set
    lateinit var extras: Extras
        private set
    /** Frames of the animated previews of videos, and the switch that turns them on. */
    lateinit var videoFrames: VideoFrames
        private set
    lateinit var videoFramePrefs: VideoFramePrefs
        private set
    /** The music player, connected to its service the first time a song is played. */
    val music: MusicController by lazy { MusicController(this) }

    /** Photos just deleted from the NAS, so every screen that shows them can drop them. */
    val deletedPhotos = MutableSharedFlow<List<RemoteEntry>>(extraBufferCapacity = 16)

    /** Something was written to the NAS (a folder, or null when it could be anywhere): the gallery looks again. */
    val nasChanged = MutableSharedFlow<NasChange?>(extraBufferCapacity = 16)

    override fun onCreate() {
        super.onCreate()
        repository = NasRepository(CredentialStore(this))
        extras = Extras(this)
        editSessions = EditSessionStore(this)
        folders = FolderStore(this)
        backupSettings = BackupSettingsStore(this)
        backupLedger = BackupLedger(File(filesDir, "backup-ledger.txt"))
        galleryPrefs = GalleryPrefs(this)
        transfers = TransferQueue(repository, isConnectionLost = { it.isConnectionLost() }, describe = { it.readable() })
        // the service keeps the app alive while there is work, also with the app closed
        MainScope().launch {
            transfers.jobs.map { jobs -> jobs.any { it.busy } }.distinctUntilChanged().collect { busy ->
                if (busy) TransferService.start(this@SmbCommanderApp)
            }
        }
        // catch up on photos taken while the app was closed (still waits for Wi-Fi)
        BackupScheduler.runNow(this, backupSettings.load(), respectCharging = true)
        thumbnails = ThumbnailCache(File(cacheDir, "images"), repository, AspectRatios(File(filesDir, "aspect-ratios.txt")))
        videoFrames = VideoFrames(File(cacheDir, "images/" + VideoFrames.FOLDER), repository) { thumbnails.key(Thumb(it)) }
        videoFramePrefs = VideoFramePrefs(this)
        photoSorter = PhotoSorter(File(filesDir, PhotoSorter.FILE_NAME), thumbnails)
        shareCache = ShareCache(this)
        Thread {
            runCatching { thumbnails.trim() }
            runCatching { videoFrames.trim() }
            // copies of an earlier share that nothing deleted (the phone restarted, say)
            runCatching { shareCache.clean(olderThanMs = 10 * 60_000L) }
        }.start()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(ThumbnailCache.Fetcher.Factory(thumbnails)) }
            .crossfade(true)
            .build()
}

/** A folder of the NAS whose content just changed. */
data class NasChange(val share: String, val path: String) {
    /**
     * affects() - tells whether a change on the NAS concerns a folder
     * @param folder the folder shown
     *
     * Return: true when the change is inside [folder], or moved or replaced a folder that contains it.
     */
    fun affects(folder: SavedFolder): Boolean {
        if (share != folder.share) return false
        val changed = RemotePath.normalize(path)
        val shown = RemotePath.normalize(folder.path)
        return changed == shown || shown.isEmpty() || changed.isEmpty() ||
            changed.startsWith("$shown/") || shown.startsWith("$changed/")
    }
}
