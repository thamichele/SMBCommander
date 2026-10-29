package io.github.thamichele.smbcommander.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.data.SavedFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private fun update(settings: BackupSettings) {
        _settings.value = settings
        store.save(settings)
        BackupScheduler.apply(app, settings)
    }
}
