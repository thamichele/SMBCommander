package io.github.thamichele.smbcommander.backup

import android.app.Application
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.BackupPlacement
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.data.SavedFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Libera spazio": photos on the phone that are verified to be on the NAS, and may be deleted from the phone. */
sealed interface FreeSpace {
    data object Idle : FreeSpace
    data class Checking(val done: Int, val total: Int) : FreeSpace
    /** [continuing]: a batch was just deleted and the dialog for the next one should follow. */
    data class Ready(val items: List<MediaItem>, val continuing: Boolean = false) : FreeSpace {
        val bytes: Long get() = items.sumOf { it.size }
    }
    data class Freed(val count: Int, val bytes: Long) : FreeSpace
    data class Failed(val message: String) : FreeSpace
}

/** What the backup screen shows about a run in progress. */
data class BackupProgress(val running: Boolean, val done: Int, val total: Int)

class BackupViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmbCommanderApp
    private val store = app.backupSettings

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<BackupSettings> = _settings.asStateFlow()

    private val _status = MutableStateFlow(store.status())
    val status: StateFlow<BackupStatus> = _status.asStateFlow()

    private val _albums = MutableStateFlow<List<Album>>(emptyList())
    val albums: StateFlow<List<Album>> = _albums.asStateFlow()

    private val _savedCount = MutableStateFlow(0)
    val savedCount: StateFlow<Int> = _savedCount.asStateFlow()

    val folders: List<SavedFolder> get() = app.folders.all()

    private val wm = WorkManager.getInstance(application)

    val progress: StateFlow<BackupProgress> = combine(
        wm.getWorkInfosForUniqueWorkFlow(BackupScheduler.PERIODIC),
        wm.getWorkInfosForUniqueWorkFlow(BackupScheduler.NOW),
    ) { periodic, now ->
        val running = (periodic + now).firstOrNull { it.state == WorkInfo.State.RUNNING }
        if (running == null) {
            // a run just ended: refresh the summary
            _status.value = store.status()
            _savedCount.value = app.backupLedger.size()
            BackupProgress(false, 0, 0)
        } else {
            BackupProgress(
                true,
                running.progress.getInt(PhotoBackupWorker.PROGRESS_DONE, 0),
                running.progress.getInt(PhotoBackupWorker.PROGRESS_TOTAL, 0),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BackupProgress(false, 0, 0))

    /** Reads albums once the gallery permission is there. */
    fun loadAlbums() = viewModelScope.launch {
        if (!PhotoBackupWorker.hasMediaPermission(app)) return@launch
        val scanner = MediaScanner(app)
        val list = withContext(Dispatchers.IO) { scanner.albums(_settings.value.includeVideos) }
        _albums.value = list
        _savedCount.value = withContext(Dispatchers.IO) { app.backupLedger.size() }
        // first time: preselect the camera
        if (_settings.value.albums.isEmpty()) {
            scanner.cameraAlbum(list)?.let { update(_settings.value.copy(albums = setOf(it.id))) }
        }
    }

    fun setEnabled(on: Boolean) {
        update(_settings.value.copy(enabled = on))
        if (on) runNow()
    }

    fun setDestination(folder: SavedFolder) = update(_settings.value.copy(destination = folder))

    fun toggleAlbum(id: String) {
        val current = _settings.value.albums
        update(_settings.value.copy(albums = if (id in current) current - id else current + id))
    }

    fun setIncludeVideos(on: Boolean) {
        update(_settings.value.copy(includeVideos = on))
        loadAlbums()
    }

    fun setByMonth(on: Boolean) = update(_settings.value.copy(byMonth = on))

    fun setChargingOnly(on: Boolean) = update(_settings.value.copy(chargingOnly = on))

    fun runNow() = BackupScheduler.runNow(app, _settings.value)

    private val _freeSpace = MutableStateFlow<FreeSpace>(FreeSpace.Idle)
    val freeSpace: StateFlow<FreeSpace> = _freeSpace.asStateFlow()
    private var freeJob: Job? = null

    /**
     * Finds the photos of the chosen albums that are safely on the NAS: a file in the backup folder with the same size
     * and the same SHA-256. The ledger alone is not proof, as the file may have been moved or deleted on the NAS since.
     */
    fun checkFreeSpace() {
        val settings = _settings.value
        val destination = settings.destination ?: return
        freeJob?.cancel()
        _freeSpace.value = FreeSpace.Checking(0, 0)
        freeJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = MediaScanner(app).items(settings.albums, settings.includeVideos)
                val fs = app.repository.fs()
                val safe = ArrayList<MediaItem>()
                for ((i, item) in items.withIndex()) {
                    ensureActive()
                    _freeSpace.value = FreeSpace.Checking(i, items.size)
                    val folder = PhotoBackupWorker.folderFor(destination.path, item, settings.byMonth)
                    val target = RemotePath.child(folder, PhotoBackupWorker.remoteName(item))
                    // a photo unreadable on the phone hashes to "", which matches nothing: it stays
                    val placement = BackupPlacement.find(fs, destination.share, target, item.size) {
                        runCatching { PhotoBackupWorker.openOriginal(app, item).use { BackupPlacement.sha256(it) } }
                            .getOrDefault("")
                    }
                    if (placement is BackupPlacement.AlreadyThere) safe += item
                }
                _freeSpace.value = FreeSpace.Ready(safe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _freeSpace.value = FreeSpace.Failed("NAS non raggiungibile: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private var freedCount = 0
    private var freedBytes = 0L

    /**
     * The system dialog that deletes the next batch from the phone. Batches keep the request small enough for Android;
     * after each confirmed one the screen asks for the next.
     */
    fun deleteRequest(): IntentSender? {
        val ready = _freeSpace.value as? FreeSpace.Ready ?: return null
        if (ready.items.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (ready.continuing) _freeSpace.value = ready.copy(continuing = false)
        val batch = ready.items.take(DELETE_BATCH).map { it.uri }
        return MediaStore.createDeleteRequest(app.contentResolver, batch).intentSender
    }

    /** [deleted] is false when the user said no in the system dialog: then nothing is deleted. */
    fun onDeleteResult(deleted: Boolean) {
        val ready = _freeSpace.value as? FreeSpace.Ready ?: return
        if (!deleted) {
            if (freedCount > 0) finishFreeing()
            return
        }
        val batch = ready.items.take(DELETE_BATCH)
        freedCount += batch.size
        freedBytes += batch.sumOf { it.size }
        val left = ready.items.drop(DELETE_BATCH)
        if (left.isEmpty()) finishFreeing() else _freeSpace.value = FreeSpace.Ready(left, continuing = true)
    }

    private fun finishFreeing() {
        _freeSpace.value = FreeSpace.Freed(freedCount, freedBytes)
        freedCount = 0
        freedBytes = 0
        loadAlbums()
    }

    fun resetFreeSpace() {
        freeJob?.cancel()
        freedCount = 0
        freedBytes = 0
        _freeSpace.value = FreeSpace.Idle
    }

    private companion object {
        const val DELETE_BATCH = 1000
    }

    private fun update(settings: BackupSettings) {
        _settings.value = settings
        store.save(settings)
        BackupScheduler.apply(app, settings)
    }
}
