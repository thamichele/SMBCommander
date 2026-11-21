/*
 * GalleryViewModel.kt - state and actions of the gallery
 *
 * Shows at once what the phone knows, then walks the backup folder for what changed; deletes, moves to albums and
 * shares. Only the backup folder of the main NAS is shown.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.Album
import io.github.thamichele.smbcommander.core.Albums
import io.github.thamichele.smbcommander.core.Conflict
import io.github.thamichele.smbcommander.core.GalleryDates
import io.github.thamichele.smbcommander.core.GalleryIndex
import io.github.thamichele.smbcommander.core.NasException
import io.github.thamichele.smbcommander.core.NasFileSystem
import io.github.thamichele.smbcommander.core.PhotoKind
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.core.TransferLog
import io.github.thamichele.smbcommander.core.Transfers
import io.github.thamichele.smbcommander.core.unlessGone
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.data.isMedia
import io.github.thamichele.smbcommander.ui.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
import kotlinx.coroutines.withContext
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
    /** Every folder of the backup folder that is an album, in display order. */
    val albums: List<Album> = emptyList(),
    /** The folder of the album shown; null for the whole gallery. */
    val album: String? = null,
    /** The list of the albums is on screen instead of the photos. */
    val albumsOpen: Boolean = false,
) {
    /** All photos in display order, newest first. */
    val photos: List<RemoteEntry> by lazy { months.flatMap { it.photos } }

    /** The photos on screen in display order, as the viewer swipes through them. */
    val shownPhotos: List<RemoteEntry> by lazy { shown.flatMap { it.photos } }
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

    /** Reads from the phone what the gallery knew last time; a walk of the NAS never runs before it has ended. */
    private var loadJob: Job? = null
    private var index = GalleryIndex()
    private var lastWalk = 0L

    /* The backup folder changed while the app was in the background: looked at again when it comes back. */
    private var changedWhileAway = false

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = NOTICE_BUFFER)
    /** Short messages about the albums (a name refused, nothing to move), for the screen to show. */
    val notices: SharedFlow<String> = _notices

    /** Deleted from the gallery while a walk may have listed them already: kept out until the next walk. */
    private val deleted = ConcurrentHashMap.newKeySet<String>()

    /** Dates worked out once per file, not at every regrouping. */
    private val dates = ConcurrentHashMap<RemoteEntry, GalleryDates.Taken>()

    /** Categories already known, so the filter does not ask the sorter again at every regrouping. */
    private val kinds = ConcurrentHashMap<RemoteEntry, PhotoKind>()

    /** Sorting into categories starts the first time a category is chosen, and then follows the gallery. */
    private val sortWanted = MutableStateFlow(needsSorting(app.galleryPrefs.kind))

    init {
        viewModelScope.launch {
            app.deletedPhotos.collect { gone ->
                val keys = gone.map { key(it) }
                deleted += keys
                index.remove(::isDeleted)
                publish()
                _state.value.folder?.let { folder -> launch(Dispatchers.IO) { store.save(folder, index.stored(folder.share)) } }
            }
        }
        viewModelScope.launch {
            // thumbnails made in the background, whatever part of the gallery is on screen: started again (from the
            // newest photos) whenever the gallery changes, after a pause that leaves the NAS to the screen first
            // only while the app is on screen: in the background it would only use the battery and the Wi-Fi
            combine(_state.map { it.months }.distinctUntilChanged(), app.inFront) { months, front -> months to front }
                .collectLatest { (months, front) ->
                if (months.isEmpty() || !front) return@collectLatest
                delay(3_000)
                runCatching { app.thumbnails.warmUp(months.flatMap { it.photos }) }
                    .onFailure { if (it is CancellationException) throw it }
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            // the photos not sorted yet are looked at on the phone, newest first, in the background lane of the
            // thumbnails; started again when the gallery changes
            combine(_state.map { it.months }.distinctUntilChanged(), sortWanted, app.inFront) { months, wanted, front ->
                Triple(months, wanted, front)
            }
                .collectLatest { (months, wanted, front) ->
                    // looking at photos on the phone is heavy work: only while the app is on screen
                    if (!wanted || !front || months.isEmpty()) return@collectLatest
                    sortPhotos(months.flatMap { it.photos })
                }
        }
        viewModelScope.launch {
            // a backup run, an upload or a copy into the backup folder: look again, quietly
            app.nasChanged.collect { change ->
                val folder = _state.value.folder ?: return@collect
                if (change == null || change.affects(folder)) {
                    delay(1_000)
                    // a backup run in the background does not walk the NAS again: the walk waits for the app
                    if (app.inFront.value) refresh() else changedWhileAway = true
                }
            }
        }
        viewModelScope.launch {
            app.inFront.collect { front ->
                if (front && changedWhileAway) {
                    changedWhileAway = false
                    refresh()
                }
            }
        }
    }

    /**
     * ensureLoaded() - shows the gallery: from the phone at once, then a refresh when the last one is old or the
     * folder changed
     *
     * Return: nothing.
     */
    fun ensureLoaded() {
        val folder = app.backupSettings.load().destination
        val current = _state.value
        if (folder != current.folder) {
            job?.cancel()
            loadJob?.cancel()
            index = GalleryIndex()
            kinds.clear()
            _state.value = GalleryState(folder = folder, view = current.view, kind = current.kind, loading = folder != null)
            lastWalk = 0
            if (folder == null) return publish()
            // reading and grouping thousands of photos is kept off the main thread: the screen opens at once
            loadJob = viewModelScope.launch {
                val known = withContext(Dispatchers.IO) { GalleryIndex(store.load(folder)) }
                index = known
                withContext(Dispatchers.Default) { publish() }
                loadJob = null
                refresh()
            }
            return
        }
        if (folder != null && !_state.value.loading && System.currentTimeMillis() - lastWalk > AUTO_REFRESH_MS) refresh()
    }

    /**
     * setKind() - shows only one category of photos
     * @param kind the category, or null for all of them
     *
     * Return: nothing.
     */
    fun setKind(kind: PhotoKind?) {
        app.galleryPrefs.kind = kind
        if (needsSorting(kind)) sortWanted.value = true
        _state.update { it.copy(kind = kind) }
        publish()
    }

    /* Private: videos are known by their name alone; only the other categories need the photos looked at. */
    private fun needsSorting(kind: PhotoKind?): Boolean = kind != null && kind != PhotoKind.Video

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

    /**
     * setView() - chooses how the gallery shows the photos, and remembers it
     * @param view grid, mosaic or list
     *
     * Return: nothing.
     */
    fun setView(view: GalleryView) {
        app.galleryPrefs.view = view
        _state.update { it.copy(view = view) }
    }

    /**
     * pull() - pull to refresh or the button: like [refresh], with the spinner
     *
     * Return: nothing.
     */
    fun pull() = refresh(pulling = true)

    /**
     * refresh() - walks the backup folder again for what changed on the NAS
     * @param pulling shows the spinner of the pull to refresh
     *
     * Return: nothing; the gallery changes only where the NAS changed.
     */
    fun refresh(pulling: Boolean = false) {
        val folder = _state.value.folder ?: app.backupSettings.load().destination ?: return
        if (folder != _state.value.folder) return ensureLoaded()
        // what the phone knows is still being read: the walk starts by itself right after
        if (loadJob?.isActive == true) return
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
                store.save(folder, idx.stored(folder.share))
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
        val media = visible.filter { it.isMedia && !isDeleted(it) }
        // newest folders first, so "2026/10" shows before "2019/01"
        val dirs = visible.filter { it.isDirectory }.sortedByDescending { it.name }
        // a folder with nothing in it is an album to fill; the backup folder itself is not
        if (idx.replace(dir, media, isEmptyAlbum = depth > 0 && visible.isEmpty())) dirty = true
        if (depth >= MAX_DEPTH) return
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

    /* The months worked out from one version of the index: a change of tab, album or category only filters them. */
    private class Grouped(val index: GalleryIndex, val version: Long, val months: List<MonthGroup>)

    @Volatile
    private var grouped: Grouped? = null

    /* The photos by month, newest first; sorted again only when the index changed since the last time. */
    private fun months(): List<MonthGroup> {
        val idx = index
        val version = idx.version
        grouped?.takeIf { it.index === idx && it.version == version }?.let { return it.months }
        val months = idx.all()
            .groupBy { taken(it).let { t -> t.year to t.month } }
            .map { (ym, list) ->
                MonthGroup(ym.first, ym.second, list.sortedWith(compareByDescending<RemoteEntry> { taken(it).sortKey }.thenByDescending { it.name }))
            }
            .sortedWith(compareByDescending<MonthGroup> { it.year }.thenByDescending { it.month })
        grouped = Grouped(idx, version, months)
        return months
    }

    private fun publish() {
        dirty = false
        val months = months()
        val counts = HashMap<PhotoKind, Int>()
        for (m in months) for (p in m.photos) kindOf(p)?.let { counts[it] = (counts[it] ?: 0) + 1 }
        val folders = index.folders()
        val emptyFolders = index.emptyFolders()
        _state.update { st ->
            val root = RemotePath.normalize(st.folder?.path.orEmpty())
            val albums = if (st.folder == null) emptyList() else Albums.list(root, folders, emptyFolders) { taken(it).sortKey }
            st.copy(months = months, shown = filtered(months, st.album, st.kind), kindCounts = counts, albums = albums)
        }
    }

    /** The months with only the photos of [album] and of [kind]; null leaves that filter out. */
    private fun filtered(months: List<MonthGroup>, album: String?, kind: PhotoKind?): List<MonthGroup> {
        if (album == null && kind == null) return months
        val isShown = { e: RemoteEntry -> (album == null || Albums.holds(album, e.path)) && (kind == null || kindOf(e) == kind) }
        return months.mapNotNull { m -> m.photos.filter(isShown).takeIf { it.isNotEmpty() }?.let { m.copy(photos = it) } }
    }

    /**
     * openAlbums() - shows the list of the albums in place of the photos
     *
     * Return: nothing.
     */
    fun openAlbums() {
        leaveVideos()
        _state.update { it.copy(albumsOpen = true) }
    }

    /**
     * openPhotos() - the "Foto" tab: the whole gallery by date, out of the albums and of the videos
     *
     * Return: nothing.
     */
    fun openPhotos() {
        leaveVideos()
        _state.update { it.copy(album = null, albumsOpen = false) }
        publish()
    }

    /**
     * openVideos() - the "Video" tab: only the videos of the whole gallery, by date
     *
     * It is the category [PhotoKind.Video] under another name, so the tab and the button of the categories
     * always agree.
     *
     * Return: nothing.
     */
    fun openVideos() {
        _state.update { it.copy(album = null, albumsOpen = false) }
        setKind(PhotoKind.Video)
    }

    /* Private: the three tabs exclude each other: leaving "Video" for another one drops its filter. */
    private fun leaveVideos() {
        if (_state.value.kind != PhotoKind.Video) return
        app.galleryPrefs.kind = null
        _state.update { it.copy(kind = null) }
    }

    /**
     * openAlbum() - shows only the photos of one album
     * @param path the folder of the album, as in [Album.path]
     *
     * Return: nothing.
     */
    fun openAlbum(path: String) {
        _state.update { it.copy(album = path, albumsOpen = false) }
        publish()
    }

    /**
     * leaveAlbums() - one step back: from an album to the list of the albums, from the list to all the photos
     *
     * Return: false when neither was open, so Back is for someone else.
     */
    fun leaveAlbums(): Boolean {
        val st = _state.value
        if (st.album == null && !st.albumsOpen) return false
        _state.update { it.copy(album = null, albumsOpen = it.album != null) }
        publish()
        return true
    }

    /**
     * createAlbum() - creates the folder of a new album, directly in the backup folder
     * @param name the name typed by the user
     * @param onCreated called on the main thread with the path of the new folder, once it exists on the NAS
     *
     * A name already taken by any folder or file is refused: nothing is merged or replaced. A refused name or a
     * failure is said through [notices].
     *
     * Context: returns at once; the NAS is asked in the background.
     * Return: nothing.
     */
    fun createAlbum(name: String, onCreated: (String) -> Unit = {}) {
        val folder = _state.value.folder ?: return
        val path = try {
            Albums.newPath(folder.path, name)
        } catch (e: IllegalArgumentException) {
            _notices.tryEmit(e.message.orEmpty())
            return
        }
        val idx = index
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val fs = app.repository.fs()
                    if (fs.stat(folder.share, path) != null) throw NasException("Esiste già una cartella chiamata «${name.trim()}»")
                    fs.createDirectory(folder.share, path)
                    idx.addEmpty(path)
                    store.save(folder, idx.stored(folder.share))
                }
                publish()
                onCreated(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notices.tryEmit(e.readable())
            }
        }
    }

    /**
     * moveToAlbum() - moves photos into the folder of an album, as one job of the transfer queue
     * @param photos the photos to move; those already in the album are left out
     * @param album the folder of the album, as in [Album.path]
     *
     * The move is [Transfers.move], the same of the file browser: inside the backup folder a rename on the NAS,
     * with no data travelling, and a copy verified by SHA-256 before the original goes wherever a rename cannot
     * do. A name already taken in the album gets " (2)": nothing is overwritten.
     *
     * Context: returns at once; the job goes on with the app closed, see TransferService.
     * Return: nothing.
     */
    fun moveToAlbum(photos: List<RemoteEntry>, album: String) {
        val folder = _state.value.folder ?: return
        val moving = photos.filterNot { Albums.holds(album, it.path) }
        if (moving.isEmpty()) {
            _notices.tryEmit("Le foto sono già in questo album")
            return
        }
        val name = Albums.nameOf(RemotePath.normalize(folder.path), album).ifEmpty { folder.fullPath }
        val what = if (moving.size == 1) "«${moving[0].name}»" else "${moving.size} foto"
        val log = TransferLog()
        val idx = index
        app.transfers.enqueue("Sposta $what in «$name»") { fs, progress ->
            try {
                moveAll(fs, moving, folder.share, album, log, idx, progress)
            } finally {
                // what was moved shows in its album at once, also when the job stops half way
                publish()
            }
        }
    }

    /**
     * Moves [photos] one by one and tells [idx] where each went. Runs on the thread of the transfer queue, maybe
     * more than once: [log] remembers what is done. Returns the note for the end of the job, or null.
     */
    private fun moveAll(
        fs: NasFileSystem,
        photos: List<RemoteEntry>,
        share: String,
        album: String,
        log: TransferLog,
        idx: GalleryIndex,
        progress: (name: String, done: Long, total: Long) -> Unit,
    ): String? {
        var gone = 0
        for ((i, photo) in photos.withIndex()) {
            // a pause or a cancel interrupts the thread: stop between two photos, never in the middle of one
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            progress(photo.name, i.toLong(), photos.size.toLong())
            val listener = Transfers.Listener { _, _, _ -> }
            val target = fs.unlessGone(photo.share, photo.path) {
                Transfers.move(fs, photo, share, album, listener, Conflict.KeepBoth, log)
            }
            if (target == null) gone++ else idx.move(photo, target)
        }
        return when (gone) {
            0 -> null
            1 -> "1 foto non era più sul NAS, saltata"
            else -> "$gone foto non erano più sul NAS, saltate"
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
        /** Notices waiting for the screen: more than a few at once would be noise anyway. */
        const val NOTICE_BUFFER = 4

        /**
         * hidden() - tells whether a name is left out of the gallery
         * @param name a file or folder name
         *
         * Hidden files and the NAS's own folders: recycle bins, thumbnail caches, snapshots, temporary office files.
         *
         * Return: true when it is left out.
         */
        fun hidden(name: String) = name.startsWith(".") || name.startsWith("@") || name.startsWith("#") ||
            name.equals("Thumbs.db", ignoreCase = true) || name.startsWith("~")
    }
}
