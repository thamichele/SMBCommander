package io.github.thamichele.smbcommander.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.NasFileSystem
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.data.isImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/** Photos of one month, newest month first. */
data class MonthGroup(val year: Int, val month: Int, val photos: List<RemoteEntry>)

data class GalleryState(
    val folder: SavedFolder? = null,
    val months: List<MonthGroup> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** A list with names and details instead of the grid of thumbnails. */
    val listView: Boolean = false,
) {
    /** All photos in display order, as the viewer swipes through them. */
    val photos: List<RemoteEntry> get() = months.flatMap { it.photos }
}

/**
 * The photos in the backup folder and nowhere else. Folders are walked a few levels deep ("2026/10"), and the grid
 * fills in as each folder arrives.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmbCommanderApp
    private val _state = MutableStateFlow(GalleryState(listView = app.galleryPrefs.listView))
    val state: StateFlow<GalleryState> = _state.asStateFlow()
    private var job: Job? = null

    /** Deleted while a walk may still be running: kept out of what it finds. */
    private val deleted = ConcurrentHashMap.newKeySet<String>()

    init {
        viewModelScope.launch {
            app.deletedPhotos.collect { gone ->
                deleted += gone.map { key(it) }
                _state.update { s -> s.copy(months = s.months.withoutDeleted()) }
            }
        }
    }

    /** Loads on first show, or again when the backup folder changed meanwhile. */
    fun ensureLoaded() {
        val folder = app.backupSettings.load().destination
        val current = _state.value
        if (folder == current.folder && (current.months.isNotEmpty() || current.loading)) return
        refresh()
    }

    fun toggleView() {
        val list = !_state.value.listView
        app.galleryPrefs.listView = list
        _state.update { it.copy(listView = list) }
    }

    fun refresh() {
        val folder = app.backupSettings.load().destination
        job?.cancel()
        // a fresh walk only finds what is still on the NAS
        deleted.clear()
        if (folder == null) {
            _state.value = GalleryState(listView = _state.value.listView)
            return
        }
        _state.value = _state.value.copy(folder = folder, loading = true, error = null)
        job = viewModelScope.launch(Dispatchers.IO) {
            val found = ArrayList<RemoteEntry>()
            try {
                val fs = app.repository.fs()
                walk(fs, folder.share, folder.path, 0) { batch ->
                    found += batch
                    _state.update { it.copy(months = group(found.filterNot(::isDeleted))) }
                }
                _state.update { it.copy(months = group(found.filterNot(::isDeleted)), loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun walk(fs: NasFileSystem, share: String, path: String, depth: Int, found: (List<RemoteEntry>) -> Unit) {
        currentCoroutineContext().ensureActive()
        val entries = fs.list(share, path).filterNot { it.name.startsWith(".") || it.name.startsWith("@") }
        val photos = entries.filter { it.isImage }
        if (photos.isNotEmpty()) found(photos)
        if (depth >= MAX_DEPTH) return
        // newest folders first, so "2026/10" shows before "2019/01"
        for (dir in entries.filter { it.isDirectory }.sortedByDescending { it.name }) walk(fs, share, dir.path, depth + 1, found)
    }

    private fun group(photos: List<RemoteEntry>): List<MonthGroup> =
        photos.groupBy { monthOf(it) }
            .map { (ym, list) -> MonthGroup(ym.first, ym.second, list.sortedByDescending { it.name }) }
            .sortedWith(compareByDescending<MonthGroup> { it.year }.thenByDescending { it.month })

    /** From a "…/2026/10/…" folder when the backup sorts by month, otherwise from the file date. */
    private fun monthOf(photo: RemoteEntry): Pair<Int, Int> {
        val parts = photo.path.split('/')
        for (i in parts.size - 2 downTo 1) {
            val year = parts[i - 1].toIntOrNull()
            val month = parts[i].toIntOrNull()
            if (year != null && month != null && year in 1970..2100 && month in 1..12) return year to month
        }
        val cal = Calendar.getInstance().apply { timeInMillis = photo.modified }
        return cal.get(Calendar.YEAR) to cal.get(Calendar.MONTH) + 1
    }

    private fun key(e: RemoteEntry) = e.share + "/" + e.path

    /** The photo itself, or a folder that contained it, was deleted. */
    private fun isDeleted(e: RemoteEntry): Boolean {
        if (deleted.isEmpty()) return false
        val k = key(e)
        return deleted.any { k == it || k.startsWith("$it/") }
    }

    private fun List<MonthGroup>.withoutDeleted(): List<MonthGroup> =
        map { g -> g.copy(photos = g.photos.filterNot(::isDeleted)) }.filter { it.photos.isNotEmpty() }

    private companion object {
        const val MAX_DEPTH = 3
    }
}
