package io.github.thamichele.smbcommander.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.GalleryDates
import io.github.thamichele.smbcommander.core.GalleryIndex
import io.github.thamichele.smbcommander.core.NasFileSystem
import io.github.thamichele.smbcommander.core.PhotoKind
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.data.isMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** How the gallery shows the photos. */
enum class GalleryView(val label: String) {
    /** Small square tiles. */
    Grid("Griglia"),

    /** Bigger photos in rows, each with its own shape, fitting together edge to edge. */
    Mosaic("Mosaico"),

    /** Names and details, where a swipe deletes. */
    List("Elenco"),
}

/** Photos of one month, newest month first. */
data class MonthGroup(val year: Int, val month: Int, val photos: List<RemoteEntry>)

data class GalleryState(
    val folder: SavedFolder? = null,
    val months: List<MonthGroup> = emptyList(),
    /** A walk of the NAS is running; what is shown stays usable meanwhile. */
    val loading: Boolean = false,
    /** The user pulled to refresh: only then the spinner shows over the photos. */
    val pulling: Boolean = false,
    val error: String? = null,
    val view: GalleryView = GalleryView.Grid,
    /** The category shown; null for all the photos. */
    val kind: PhotoKind? = null,
    /** The months of [kind] only; the same as [months] when all the photos are shown. */
    val shown: List<MonthGroup> = emptyList(),
    /** How many photos of each category are known so far. */
    val kindCounts: Map<PhotoKind, Int> = emptyMap(),
    /** Photos still to be sorted into categories, while it goes on. */
    val sorting: Int = 0,
) {
    /** All photos in display order, newest first. */
    val photos: List<RemoteEntry> get() = months.flatMap { it.photos }

    /** The photos on screen in display order, as the viewer swipes through them. */
    val shownPhotos: List<RemoteEntry> get() = shown.flatMap { it.photos }
}

/**
 * The photos and videos in the backup folder and nowhere else, at any depth and in any folder layout (the backup's
 * "2026/10", older QuMagie or camera-upload folders). The last known content is shown at once; a refresh lists the
 * folders again, several at a time, and changes the gallery only where the NAS changed.
 */
class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmbCommanderApp
    private val store = GalleryStore(File(application.filesDir, "gallery"))
    private val _state = MutableStateFlow(GalleryState(view = app.galleryPrefs.view, kind = app.galleryPrefs.kind))
    val state: StateFlow<GalleryState> = _state.asStateFlow()
    private var job: Job? = null
    private var index = GalleryIndex()
    private var lastWalk = 0L

    /** Deleted from the gallery while a walk may have listed them already: kept out until the next walk. */
    private val deleted = ConcurrentHashMap.newKeySet<String>()

    /** Dates worked out once per file, not at every regrouping. */
    private val dates = ConcurrentHashMap<RemoteEntry, GalleryDates.Taken>()

    /** Categories already known, so the filter does not ask the sorter again at every regrouping. */
    private val kinds = ConcurrentHashMap<RemoteEntry, PhotoKind>()

    /** Sorting into categories starts the first time a category is chosen, and then follows the gallery. */
    private val sortWanted = MutableStateFlow(app.galleryPrefs.kind != null)

    init {
        viewModelScope.launch {
            app.deletedPhotos.collect { gone ->
                val keys = gone.map { key(it) }
                deleted += keys
                index.remove(::isDeleted)
                publish()
                _state.value.folder?.let { folder -> launch(Dispatchers.IO) { store.save(folder, index.all()) } }
            }
        }
        viewModelScope.launch {
            // thumbnails made in the background, whatever part of the gallery is on screen: started again (from the
            // newest photos) whenever the gallery changes, after a pause that leaves the NAS to the screen first
            _state.map { it.months }.distinctUntilChanged().collectLatest { months ->
                if (months.isEmpty()) return@collectLatest
                delay(3_000)
                runCatching { app.thumbnails.warmUp(months.flatMap { it.photos }) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // the photos not sorted yet are looked at on the phone, newest first, in the background lane of the
            // thumbnails; started again when the gallery changes
            combine(_state.map { it.months }.distinctUntilChanged(), sortWanted) { months, wanted -> months to wanted }
                .collectLatest { (months, wanted) ->
                    if (!wanted || months.isEmpty()) return@collectLatest
                    sortPhotos(months.flatMap { it.photos })
                }
        }
        viewModelScope.launch {
            // a backup run, an upload or a copy into the backup folder: look again, quietly
            app.nasChanged.collect { change ->
                val folder = _state.value.folder ?: return@collect
                if (change == null || change.affects(folder)) {
                    delay(1_000)
                    refresh()
                }
            }
        }
    }

    /** Shows the gallery: from the phone at once, then a refresh when the last one is old or the folder changed. */
    fun ensureLoaded() {
        val folder = app.backupSettings.load().destination
        val current = _state.value
        if (folder != current.folder) {
            job?.cancel()
            index = GalleryIndex(folder?.let(store::load).orEmpty())
            kinds.clear()
            _state.value = GalleryState(folder = folder, view = current.view, kind = current.kind)
            publish()
            lastWalk = 0
        }
        if (folder != null && !_state.value.loading && System.currentTimeMillis() - lastWalk > AUTO_REFRESH_MS) refresh()
    }

    /** Shows only the photos of [kind], or all of them with null. */
    fun setKind(kind: PhotoKind?) {
        app.galleryPrefs.kind = kind
        if (kind != null) sortWanted.value = true
        _state.update { it.copy(kind = kind) }
        publish()
    }

    private suspend fun sortPhotos(photos: List<RemoteEntry>) {
        val todo = photos.filter { kindOf(it) == null }
        _state.update { it.copy(sorting = todo.size) }
        if (todo.isEmpty()) return publish()
        var left = todo.size
        var shown = System.currentTimeMillis()
        coroutineScope {
            for (batch in todo.chunked(SORT_BATCH)) {
                batch.map { photo ->
                    async { runCatching { kinds[photo] = app.photoSorter.sort(photo) }.onFailure { if (it is CancellationException) throw it } }
                }.awaitAll()
                left -= batch.size
                // the gallery follows about once a second, not at every photo
                if (left == 0 || System.currentTimeMillis() - shown > 1_000) {
                    shown = System.currentTimeMillis()
                    _state.update { it.copy(sorting = left) }
                    publish()
                }
            }
        }
    }

    private fun kindOf(e: RemoteEntry): PhotoKind? =
        kinds[e] ?: app.photoSorter.known(e)?.also { kinds[e] = it }

    fun setView(view: GalleryView) {
        app.galleryPrefs.view = view
        _state.update { it.copy(view = view) }
    }

    /** Pull to refresh or the button: like [refresh], with the spinner. */
    fun pull() = refresh(pulling = true)

    fun refresh(pulling: Boolean = false) {
        val folder = _state.value.folder ?: app.backupSettings.load().destination ?: return
        if (folder != _state.value.folder) return ensureLoaded()
        job?.cancel()
        deleted.clear()
        _state.update { it.copy(loading = true, pulling = pulling, error = null) }
        // a walk only ever writes to the index of its own folder, even if the folder changes meanwhile
        val idx = index
        job = viewModelScope.launch(Dispatchers.IO) {
            val seen = ConcurrentHashMap.newKeySet<String>()
            val failed = ConcurrentHashMap.newKeySet<String>()
            val ticker = launch {
                // the grid follows the walk a few times a second, not at every folder
                while (isActive) {
                    delay(400)
                    if (dirty) publish()
                }
            }
            try {
                val fs = app.repository.fs()
                // the backup folder itself must be readable, otherwise nothing is known
                val root = fs.list(folder.share, folder.path)
                walk(idx, fs, folder.share, RemotePath.normalize(folder.path), root, 0, seen, failed, Semaphore(PARALLEL_LISTINGS))
                if (idx.retain(seen, failed)) dirty = true
                ticker.cancel()
                publish()
                lastWalk = System.currentTimeMillis()
                store.save(folder, idx.all())
                _state.update {
                    it.copy(
                        loading = false,
                        pulling = false,
                        error = if (failed.isEmpty()) null else "Alcune cartelle non si sono potute leggere (${failed.size})",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ticker.cancel()
                _state.update { it.copy(loading = false, pulling = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    @Volatile
    private var dirty = false

    private suspend fun walk(
        idx: GalleryIndex,
        fs: NasFileSystem,
        share: String,
        dir: String,
        entries: List<RemoteEntry>,
        depth: Int,
        seen: MutableSet<String>,
        failed: MutableSet<String>,
        permits: Semaphore,
    ) {
        seen += dir
        val visible = entries.filterNot { hidden(it.name) }
        if (idx.replace(dir, visible.filter { it.isMedia && !isDeleted(it) })) dirty = true
        if (depth >= MAX_DEPTH) return
        // newest folders first, so "2026/10" shows before "2019/01"
        val dirs = visible.filter { it.isDirectory }.sortedByDescending { it.name }
        coroutineScope {
            for (sub in dirs) {
                launch {
                    val children = try {
                        permits.withPermit { fs.list(share, sub.path) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // an unreadable folder keeps what the gallery knew about it
                        failed += sub.path
                        return@launch
                    }
                    walk(idx, fs, share, sub.path, children, depth + 1, seen, failed, permits)
                }
            }
        }
    }

    private fun publish() {
        dirty = false
        val months = index.all()
            .groupBy { taken(it).let { t -> t.year to t.month } }
            .map { (ym, list) ->
                MonthGroup(ym.first, ym.second, list.sortedWith(compareByDescending<RemoteEntry> { taken(it).sortKey }.thenByDescending { it.name }))
            }
            .sortedWith(compareByDescending<MonthGroup> { it.year }.thenByDescending { it.month })
        val counts = HashMap<PhotoKind, Int>()
        for (m in months) for (p in m.photos) kindOf(p)?.let { counts[it] = (counts[it] ?: 0) + 1 }
        _state.update { st ->
            val kind = st.kind
            val shown = if (kind == null) months
            else months.mapNotNull { m -> m.photos.filter { kindOf(it) == kind }.takeIf { it.isNotEmpty() }?.let { m.copy(photos = it) } }
            st.copy(months = months, shown = shown, kindCounts = counts)
        }
    }

    private fun taken(e: RemoteEntry) = dates.getOrPut(e) { GalleryDates.of(e) }

    private fun key(e: RemoteEntry) = e.share + "/" + e.path

    /** The photo itself, or a folder that contained it, was deleted. */
    private fun isDeleted(e: RemoteEntry): Boolean {
        if (deleted.isEmpty()) return false
        val k = key(e)
        return deleted.any { k == it || k.startsWith("$it/") }
    }

    private companion object {
        /** Deep enough for any archive layout, short of a folder that links back into itself. */
        const val MAX_DEPTH = 12
        const val PARALLEL_LISTINGS = 4
        const val AUTO_REFRESH_MS = 60_000L
        /** Photos sorted together: the thumbnails missing are fetched side by side. */
        const val SORT_BATCH = 4

        /** Hidden files and the NAS's own folders: recycle bins, thumbnail caches, snapshots. */
        fun hidden(name: String) = name.startsWith(".") || name.startsWith("@") || name.startsWith("#") ||
            name.equals("Thumbs.db", ignoreCase = true) || name.startsWith("~")
    }
}
