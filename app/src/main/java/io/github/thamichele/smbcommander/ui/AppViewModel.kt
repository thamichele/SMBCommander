package io.github.thamichele.smbcommander.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thamichele.smbcommander.NasChange
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.backup.BackupScheduler
import io.github.thamichele.smbcommander.core.DeletedPhotos
import io.github.thamichele.smbcommander.core.EditSession
import io.github.thamichele.smbcommander.core.EditSync
import io.github.thamichele.smbcommander.core.Integrity
import io.github.thamichele.smbcommander.core.NasServer
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.core.SyncDecision
import io.github.thamichele.smbcommander.core.Transfers
import io.github.thamichele.smbcommander.core.unlessGone
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.data.TransferJob
import io.github.thamichele.smbcommander.data.describe
import io.github.thamichele.smbcommander.data.isMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID

sealed interface Screen {
    data object Setup : Screen
    data object Shares : Screen
    data object Backup : Screen
    data class Browser(val share: String, val path: String) : Screen

    data object Gallery : Screen

    /** The quick cleanup of the gallery: one photo at a time, swipe to keep or discard. */
    data object Cleanup : Screen

    /** Full-screen photos, swiping through [images] starting at [index]; back returns to [returnTo]. */
    data class Viewer(val returnTo: Screen, val images: List<RemoteEntry>, val index: Int) : Screen
}

enum class SortMode { Name, Date, Size }

data class Transfer(val label: String, val done: Long, val total: Long) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/** How one file of a deletion went. */
data class DeletionStep(val entry: RemoteEntry, val outcome: Outcome, val error: String? = null) {
    enum class Outcome { Deleted, Skipped, Failed }
}

/**
 * A deletion of several files, step by step: first the photos of the backup are hashed for the list of deleted ones
 * ([preparing] of [preparingTotal]), then each file is deleted in turn ([current]) and its outcome added to [steps].
 */
data class Deletion(
    val total: Int,
    val preparing: Int? = null,
    val preparingNote: String = "Calcolo le impronte per l'elenco delle eliminate",
    val preparingTotal: Int = 0,
    val current: RemoteEntry? = null,
    val steps: List<DeletionStep> = emptyList(),
    val stopping: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val left: Int get() = total - steps.size
    val deleted: List<RemoteEntry> get() = steps.filter { it.outcome == DeletionStep.Outcome.Deleted }.map { it.entry }
    val skipped: List<RemoteEntry> get() = steps.filter { it.outcome == DeletionStep.Outcome.Skipped }.map { it.entry }
    val failed: List<DeletionStep> get() = steps.filter { it.outcome == DeletionStep.Outcome.Failed }

    /** For a single file: [done] if it was deleted, otherwise why not. */
    fun message(done: String): String = steps.firstOrNull()?.let {
        when (it.outcome) {
            DeletionStep.Outcome.Deleted -> done
            DeletionStep.Outcome.Skipped -> "«${it.entry.name}» non era più sul NAS: saltato"
            DeletionStep.Outcome.Failed -> it.error ?: "«${it.entry.name}» non eliminato"
        }
    } ?: error ?: done
}

/** A file changed on the phone that cannot simply be uploaded. */
data class SyncPrompt(val session: EditSession, val remoteMissing: Boolean)

data class BrowserState(
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val sort: SortMode = SortMode.Name,
    val query: String = "",
    val searching: Boolean = false,
    val showHidden: Boolean = false,
    val grid: Boolean = false,
) {
    /** What the list shows: hidden and system entries filtered out, search applied, folders first. Worked out once. */
    val visible: List<RemoteEntry> by lazy {
        val filtered = entries.filter { e ->
            (showHidden || !(e.name.startsWith(".") || e.name.startsWith("@"))) &&
                (query.isBlank() || e.name.contains(query.trim(), ignoreCase = true))
        }
        val order = when (sort) {
            SortMode.Name -> compareBy<RemoteEntry> { it.name.lowercase() }
            SortMode.Date -> compareByDescending { it.modified }
            SortMode.Size -> compareByDescending<RemoteEntry> { it.size }.thenBy { it.name.lowercase() }
        }
        filtered.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.then(order))
    }
}

/** Entries waiting for "Incolla qui" in another folder. */
data class Clipboard(val entries: List<RemoteEntry>, val move: Boolean)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class OpenFile(val file: File, val name: String, val edit: Boolean) : UiEvent
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmbCommanderApp
    private val repo = app.repository
    private val sessions = app.editSessions

    private val _screen = MutableStateFlow<Screen>(if (repo.isConfigured) Screen.Shares else Screen.Setup)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _folders = MutableStateFlow(app.folders.all())
    val folders: StateFlow<List<SavedFolder>> = _folders.asStateFlow()

    /** Shares the NAS announces, offered as shortcuts when adding a folder. */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _checkingFolder = MutableStateFlow(false)
    val checkingFolder: StateFlow<Boolean> = _checkingFolder.asStateFlow()

    private val browserPrefs = BrowserPrefs(application)
    private val _browser = MutableStateFlow(
        BrowserState(sort = browserPrefs.sort, grid = browserPrefs.grid, showHidden = browserPrefs.showHidden)
    )
    val browser: StateFlow<BrowserState> = _browser.asStateFlow()

    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer.asStateFlow()

    private val _syncPrompt = MutableStateFlow<SyncPrompt?>(null)
    val syncPrompt: StateFlow<SyncPrompt?> = _syncPrompt.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    /** Paths selected in the folder being browsed; while not empty, taps select instead of opening. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _clipboard = MutableStateFlow<Clipboard?>(null)
    val clipboard: StateFlow<Clipboard?> = _clipboard.asStateFlow()

    val transfers: StateFlow<List<TransferJob>> = app.transfers.jobs

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    /** Listings already seen: going back shows them at once while a fresh copy loads. */
    private val listingCache = HashMap<String, List<RemoteEntry>>()
    private var listJob: Job? = null
    private val syncMutex = Mutex()

    init {
        viewModelScope.launch {
            app.transfers.finished.collect { job ->
                // a copy or move changes other folders too: forget them all
                listingCache.clear()
                app.nasChanged.tryEmit(null)
                refresh()
                if (job.state == TransferJob.State.Failed) message("${job.label}: ${job.error}")
            }
        }
    }

    fun savedServer(): NasServer? = repo.server()

    fun savedHost(): String = repo.host().orEmpty()

    // --- connection -------------------------------------------------------------------------------------------------

    /** An empty password keeps the saved one, as long as address and user did not change. */
    fun connect(server: NasServer) = viewModelScope.launch {
        _connecting.value = true
        try {
            val saved = if (server.password.isEmpty()) repo.server() else null
            repo.connect(
                if (saved != null && saved.host == server.host && saved.username == server.username) {
                    server.copy(password = saved.password)
                } else server
            )
            listingCache.clear()
            _screen.value = Screen.Shares
        } catch (e: Exception) {
            message(e.readable())
        } finally {
            _connecting.value = false
        }
    }

    fun disconnect() {
        repo.forget()
        app.folders.clear()
        // a different NAS starts from scratch: no backup towards a folder that may not exist there
        app.backupSettings.clear()
        app.backupLedger.clear()
        BackupScheduler.apply(app, app.backupSettings.load())
        // nothing of the old NAS stays on the phone: copies opened for editing, thumbnails and previews
        sessions.all().forEach(sessions::remove)
        val cache = getApplication<Application>().cacheDir
        viewModelScope.launch(Dispatchers.IO) {
            File(cache, "edit").deleteRecursively()
            File(cache, "images").deleteRecursively()
            File(getApplication<Application>().filesDir, "gallery").deleteRecursively()
            app.thumbnails.ratios.clear()
        }
        listingCache.clear()
        _folders.value = emptyList()
        _screen.value = Screen.Setup
    }

    fun openBackup() {
        _screen.value = Screen.Backup
    }

    fun openGallery() {
        _screen.value = Screen.Gallery
    }

    fun openCleanup() {
        _screen.value = Screen.Cleanup
    }

    fun openFiles() {
        _screen.value = Screen.Shares
    }

    fun openSettings() {
        _screen.value = Screen.Setup
    }

    // --- the user's folders ------------------------------------------------------------------------------------------

    fun loadSuggestions() = viewModelScope.launch {
        _suggestions.value = runCatching { repo.io { it.listShares() } }.getOrDefault(emptyList())
    }

    /** Adds a folder typed by the user ("Multimedia/Foto", "Backup$") after checking it really opens. */
    fun addFolder(input: String, onAdded: () -> Unit) {
        val folder = SavedFolder.parse(input) ?: return message("Scrivi il nome di una cartella condivisa")
        viewModelScope.launch {
            _checkingFolder.value = true
            try {
                val entry = repo.io { fs ->
                    fs.list(folder.share, folder.path)
                    if (folder.path.isEmpty()) null else fs.stat(folder.share, folder.path)
                }
                if (entry != null && !entry.isDirectory) error("«${folder.fullPath}» è un file, non una cartella")
                _folders.value = app.folders.add(folder)
                onAdded()
                message("«${folder.name}» aggiunta alle tue cartelle")
            } catch (e: Exception) {
                message("Non riesco ad aprire «${folder.fullPath}»: ${e.readable()}")
            } finally {
                _checkingFolder.value = false
            }
        }
    }

    fun removeFolder(folder: SavedFolder) {
        _folders.value = app.folders.remove(folder)
        message("«${folder.name}» tolta dall'elenco (sul NAS non cambia nulla)")
    }

    /** Adds the folder being browsed to the start screen. */
    fun pinCurrent() {
        val s = _screen.value as? Screen.Browser ?: return
        val folder = SavedFolder(s.share, s.path)
        _folders.value = app.folders.add(folder)
        message("«${folder.name}» aggiunta alle tue cartelle")
    }

    fun openSaved(folder: SavedFolder) = navigate(folder.share, folder.path)

    /**
     * A folder opened from a widget. The intent can come from any app, so only one of the user's folders (or a folder
     * inside one) is opened.
     */
    fun openFromOutside(share: String, path: String) {
        if (!repo.isConfigured) return
        val dir = RemotePath.normalize(path)
        val allowed = app.folders.all().any {
            it.share.equals(share, ignoreCase = true) && (dir == RemotePath.normalize(it.path) || isInside(dir, it.path))
        }
        if (allowed) navigate(share, dir)
    }

    // --- navigation -------------------------------------------------------------------------------------------------

    fun openFolder(entry: RemoteEntry) = navigate(entry.share, entry.path)

    fun navigate(share: String, path: String) {
        _selected.value = emptySet()
        _screen.value = Screen.Browser(share, RemotePath.normalize(path))
        _browser.update { it.copy(query = "", searching = false) }
        refresh()
    }

    /** Opens the viewer on [entry], with the other photos and videos of the folder as they are shown in the list. */
    fun openViewer(entry: RemoteEntry) {
        val s = _screen.value as? Screen.Browser ?: return
        val images = _browser.value.visible.filter { it.isMedia }
        _screen.value = Screen.Viewer(s, images, images.indexOf(entry).coerceAtLeast(0))
    }

    /** Opens the viewer from the gallery, on its own list of photos. */
    fun openViewer(images: List<RemoteEntry>, index: Int) {
        if (images.isEmpty()) return
        _screen.value = Screen.Viewer(_screen.value, images, index.coerceIn(0, images.lastIndex))
    }

    /** Returns false when there is nothing left to go back to (the activity then closes). */
    fun back(): Boolean {
        when (val s = _screen.value) {
            is Screen.Viewer -> {
                _screen.value = s.returnTo
                return true
            }
            is Screen.Browser -> {
                if (_selected.value.isNotEmpty()) {
                    _selected.value = emptySet()
                } else if (_browser.value.searching) {
                    _browser.update { it.copy(searching = false, query = "") }
                } else if (s.path.isEmpty()) {
                    listJob?.cancel()
                    _screen.value = Screen.Shares
                } else {
                    navigate(s.share, RemotePath.parent(s.path))
                }
                return true
            }
            Screen.Setup -> if (repo.isConfigured) {
                _screen.value = Screen.Shares
                return true
            }
            Screen.Cleanup -> {
                _screen.value = Screen.Gallery
                return true
            }
            Screen.Backup, Screen.Gallery -> {
                _screen.value = Screen.Shares
                return true
            }
            Screen.Shares -> Unit
        }
        return false
    }

    fun refresh() {
        val s = _screen.value as? Screen.Browser ?: return
        val key = "${s.share}/${s.path}"
        listJob?.cancel()
        _browser.update { it.copy(entries = listingCache[key] ?: emptyList(), loading = true, error = null) }
        listJob = viewModelScope.launch {
            try {
                val list = repo.io { it.list(s.share, s.path) }
                listingCache[key] = list
                if (_screen.value == s) _browser.update { it.copy(entries = list, loading = false) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (_screen.value == s) _browser.update { it.copy(loading = false, error = e.readable()) }
            }
        }
    }

    fun setSort(sort: SortMode) {
        browserPrefs.sort = sort
        _browser.update { it.copy(sort = sort) }
    }

    fun setQuery(query: String) = _browser.update { it.copy(query = query) }
    fun setSearching(on: Boolean) = _browser.update { it.copy(searching = on, query = if (on) it.query else "") }
    fun toggleHidden() = _browser.update { it.copy(showHidden = !it.showHidden).also { s -> browserPrefs.showHidden = s.showHidden } }
    fun toggleGrid() = _browser.update { it.copy(grid = !it.grid).also { s -> browserPrefs.grid = s.grid } }

    // --- file operations --------------------------------------------------------------------------------------------

    fun createFolder(name: String) = folderOperation("Cartella creata") { share, dir ->
        val path = RemotePath.child(dir, checkName(name))
        repo.io {
            if (it.stat(share, path) != null) error("Esiste già un elemento chiamato «$name»")
            it.createDirectory(share, path)
        }
    }

    fun rename(entry: RemoteEntry, newName: String) = folderOperation("Rinominato in «${newName.trim()}»") { share, _ ->
        val target = RemotePath.child(RemotePath.parent(entry.path), checkName(newName))
        if (target == entry.path) return@folderOperation
        repo.io {
            if (!target.equals(entry.path, ignoreCase = true) && it.stat(share, target) != null) {
                error("Esiste già un elemento chiamato «${newName.trim()}»")
            }
            it.rename(share, entry.path, target, replace = false)
        }
    }

    /** An empty text file, nothing more: editing it is left to the apps on the phone. */
    fun createTextFile(name: String) = folderOperation("File creato") { share, dir ->
        val clean = checkName(name).let { if (RemotePath.extension(it).isEmpty()) "$it.txt" else it }
        val path = RemotePath.child(dir, clean)
        repo.io {
            if (it.stat(share, path) != null) error("Esiste già un elemento chiamato «$clean»")
            it.upload(share, path, ByteArrayInputStream(ByteArray(0)), 0)
        }
    }

    // --- selection, copy and move -----------------------------------------------------------------------------------

    fun toggleSelected(entry: RemoteEntry) = _selected.update { if (entry.path in it) it - entry.path else it + entry.path }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun selectAll() {
        _selected.value = _browser.value.visible.mapTo(HashSet()) { it.path }
    }

    private fun selectedEntries(): List<RemoteEntry> {
        val paths = _selected.value
        return _browser.value.entries.filter { it.path in paths }
    }

    /** Puts the selection on the clipboard; it is pasted in whichever folder the user opens next. */
    fun cut(move: Boolean) {
        val entries = selectedEntries()
        if (entries.isEmpty()) return
        _clipboard.value = Clipboard(entries, move)
        _selected.value = emptySet()
        message("Apri la cartella di destinazione e premi «Incolla qui»")
    }

    fun clearClipboard() {
        _clipboard.value = null
    }

    /** Queues one job per clipboard entry, into the folder being browsed. */
    fun paste() {
        val s = _screen.value as? Screen.Browser ?: return
        val clip = _clipboard.value ?: return
        val destination = if (s.path.isEmpty()) s.share else RemotePath.name(s.path)
        for (entry in clip.entries) {
            val label = (if (clip.move) "Sposta" else "Copia") + " «${entry.name}» in $destination"
            app.transfers.enqueue(label) { fs, progress ->
                val listener = Transfers.Listener { name, done, total -> progress(name, done, total) }
                if (clip.move) Transfers.move(fs, entry, s.share, s.path, listener)
                else Transfers.copy(fs, entry, s.share, s.path, listener)
            }
        }
        _clipboard.value = null
    }

    fun cancelTransfers() = app.transfers.cancelAll()

    fun clearTransfers() = app.transfers.clearFinished()

    fun deleteSelected() {
        val entries = selectedEntries()
        _selected.value = emptySet()
        deleteInBrowser(entries)
    }

    fun delete(entry: RemoteEntry) = deleteInBrowser(listOf(entry))

    private fun deleteInBrowser(entries: List<RemoteEntry>) {
        val s = _screen.value as? Screen.Browser ?: return
        viewModelScope.launch {
            try {
                val result = deleteFromNas(entries)
                if (entries.size == 1) message(result.message("«${entries[0].name}» eliminato"))
            } catch (e: Exception) {
                // with more than one file the deletion window shows what went wrong
                if (entries.size == 1) message(e.readable())
            }
            app.nasChanged.tryEmit(NasChange(s.share, s.path))
            refresh()
        }
    }

    /**
     * Deletes photos from the NAS, from the gallery or the viewer. Those in the backup folder are first written to its
     * list of deleted photos (size and hash), so the backup does not upload them again, even after a reinstall.
     */
    fun deletePhotos(photos: List<RemoteEntry>) = viewModelScope.launch {
        try {
            val result = deleteFromNas(photos)
            if (photos.size == 1) message(result.message("Foto eliminata dal NAS"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (photos.size == 1) message(e.readable())
        }
    }

    private val _deletion = MutableStateFlow<Deletion?>(null)
    /** A deletion of several files under way, or just finished: shown file by file in a window of its own. */
    val deletion: StateFlow<Deletion?> = _deletion.asStateFlow()

    @Volatile
    private var stopDeletion = false

    /** Stops the deletion after the file being deleted now; the others stay where they are. */
    fun stopDeletion() {
        stopDeletion = true
        _deletion.update { it?.copy(stopping = true) }
    }

    fun closeDeletion() {
        if (_deletion.value?.finished == true) _deletion.value = null
    }

    /**
     * Deletes [entries] from the NAS one by one; with more than one, each step is shown by [deletion]. One that is no
     * longer there (deleted or moved from another device in the meantime) is skipped, and one that fails does not stop
     * the others. Those of the backup folder are written to its list of deleted photos first, and taken off it again
     * if they were skipped or not deleted: a photo moved elsewhere must not stay remembered as deleted.
     */
    private suspend fun deleteFromNas(entries: List<RemoteEntry>): Deletion {
        val many = entries.size > 1
        stopDeletion = false
        var state = Deletion(total = entries.size, preparing = 0)
        fun publish(next: Deletion) {
            state = next
            if (many) _deletion.value = next
        }
        publish(state)
        val remembered = try {
            rememberDeletedBackupPhotos(entries) { done, total -> publish(state.copy(preparing = done, preparingTotal = total)) }
        } catch (e: Exception) {
            publish(state.copy(preparing = null, finished = true, error = e.readable()))
            throw e
        }
        publish(state.copy(preparing = null))
        try {
            for (entry in entries) {
                if (stopDeletion) break
                publish(state.copy(current = entry))
                val outcome = try {
                    val done = repo.io { fs -> fs.unlessGone(entry.share, entry.path) { fs.delete(entry.share, entry.path) } }
                    if (done != null) DeletionStep(entry, DeletionStep.Outcome.Deleted) else DeletionStep(entry, DeletionStep.Outcome.Skipped)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    DeletionStep(entry, DeletionStep.Outcome.Failed, e.readable())
                }
                publish(state.copy(steps = state.steps + outcome))
            }
        } finally {
            publish(state.copy(current = null, finished = true))
            val deleted = state.deleted.toSet()
            // two identical photos share a line: it stays while one of them was deleted
            val stillDeleted = deleted.mapNotNull { remembered[it] }.toSet()
            val forget = remembered.filterKeys { it !in deleted }.values.filter { it !in stillDeleted }
            if (forget.isNotEmpty()) withContext(NonCancellable) { forgetDeletedBackupPhotos(forget) }
            val gone = state.deleted + state.skipped
            if (gone.isNotEmpty()) {
                app.deletedPhotos.emit(gone) // the gallery may show them too
                dropDeleted(gone)
            }
        }
        return state
    }

    /**
     * Photos and videos of the backup folder about to be deleted, wherever the deletion starts (gallery, viewer or
     * file list): their size and hash go to the folder's list of deleted photos, so the backup never uploads them again.
     */
    private suspend fun rememberDeletedBackupPhotos(entries: List<RemoteEntry>, progress: (done: Int, total: Int) -> Unit): Map<RemoteEntry, String> {
        val destination = app.backupSettings.load().destination ?: return emptyMap()
        val inBackup = entries.filter { !it.isDirectory && it.isMedia && it.share == destination.share && isInside(it.path, destination.path) }
        if (inBackup.isEmpty()) return emptyMap()
        try {
            val known = repo.io { DeletedPhotos.read(it, destination.share, destination.path) }
            val lines = LinkedHashMap<RemoteEntry, String>()
            for ((i, photo) in inBackup.withIndex()) {
                progress(i, inBackup.size)
                // a photo already gone from the NAS is skipped here and by the deletion
                val hash = repo.io { fs -> fs.unlessGone(photo.share, photo.path) { Integrity.remoteSha256(fs, photo.share, photo.path) } }
                if (hash != null) lines[photo] = DeletedPhotos.entry(photo.size, hash)
            }
            repo.io { DeletedPhotos.add(it, destination.share, destination.path, lines.values) }
            // lines that were already on the list (an earlier copy of the same photo) are never taken off it again
            return lines.filterValues { it !in known }
        } finally {
            progress(inBackup.size, inBackup.size)
        }
    }

    /** Takes lines written by [rememberDeletedBackupPhotos] off the list again, for photos that were not deleted. */
    private suspend fun forgetDeletedBackupPhotos(lines: Collection<String>) {
        val destination = app.backupSettings.load().destination ?: return
        try {
            repo.io { DeletedPhotos.remove(it, destination.share, destination.path, lines) }
        } catch (e: Exception) {
            message("Elenco delle foto eliminate non aggiornato: ${e.readable()}")
        }
    }

    private fun isInside(path: String, folder: String): Boolean {
        val dir = RemotePath.normalize(folder)
        return dir.isEmpty() || RemotePath.normalize(path).startsWith("$dir/")
    }

    private val _deleteWithoutConfirm = MutableStateFlow(app.galleryPrefs.deleteWithoutConfirm)
    /** Swiping a photo away in the gallery list deletes it at once, without asking. */
    val deleteWithoutConfirm: StateFlow<Boolean> = _deleteWithoutConfirm.asStateFlow()

    fun setDeleteWithoutConfirm(on: Boolean) {
        app.galleryPrefs.deleteWithoutConfirm = on
        _deleteWithoutConfirm.value = on
    }

    /** Takes deleted photos out of the viewer and of the folder listings already loaded. */
    private fun dropDeleted(gone: List<RemoteEntry>) {
        val keys = gone.map { it.share + "/" + it.path }.toSet()
        val isGone = { e: RemoteEntry -> e.share + "/" + e.path in keys }
        listingCache.replaceAll { _, list -> list.filterNot(isGone) }
        _browser.update { it.copy(entries = it.entries.filterNot(isGone)) }
        val s = _screen.value as? Screen.Viewer ?: return
        val left = s.images.filterNot(isGone)
        _screen.value = if (left.isEmpty()) s.returnTo else s.copy(images = left, index = s.index.coerceAtMost(left.lastIndex))
    }

    /** Copies documents picked on the phone into the current folder; names already taken get " (2)", " (3)"... */
    fun upload(uris: List<Uri>) {
        val s = _screen.value as? Screen.Browser ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            var count = 0
            try {
                for ((i, uri) in uris.withIndex()) {
                    val (name, size) = withContext(Dispatchers.IO) { resolver.describe(uri) }
                    val label = if (uris.size > 1) "Carico ${i + 1} di ${uris.size}: $name" else "Carico $name"
                    repo.io { fs ->
                        val target = EditSync.freePath(fs, s.share, RemotePath.child(s.path, name))
                        EditSync.safeUpload(fs, s.share, target, size, {
                            resolver.openInputStream(uri) ?: error("Impossibile leggere «$name»")
                        }) { done, total -> _transfer.value = Transfer(label, done, total) }
                    }
                    count++
                }
                message(if (count == 1) "File caricato" else "$count file caricati")
            } catch (e: Exception) {
                message(e.readable())
            } finally {
                _transfer.value = null
                app.nasChanged.tryEmit(NasChange(s.share, s.path))
                refresh()
            }
        }
    }

    /** Saves a NAS file into a document the user created with the system picker. */
    fun downloadTo(entry: RemoteEntry, uri: Uri) = viewModelScope.launch {
        val resolver = getApplication<Application>().contentResolver
        try {
            repo.io { fs ->
                val sent = (resolver.openOutputStream(uri) ?: error("Impossibile scrivere il file")).use { out ->
                    Integrity.download(fs, entry.share, entry.path, out) { done, total ->
                        _transfer.value = Transfer("Scarico ${entry.name}", done, total)
                    }
                }
                // read back what the phone stored: a copy that differs is removed, never left looking complete
                _transfer.value = Transfer("Verifico ${entry.name}", -1, -1)
                val stored = (resolver.openInputStream(uri) ?: error("Impossibile rileggere il file")).use { Integrity.sha256(it) }
                if (stored != sent) {
                    runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                    throw Integrity.mismatch(entry.name, "la copia sul telefono è diversa")
                }
            }
            message("«${entry.name}» salvato sul telefono")
        } catch (e: Exception) {
            message(e.readable())
        } finally {
            _transfer.value = null
        }
    }

    // --- open and edit ----------------------------------------------------------------------------------------------

    /**
     * Downloads [entry] into a private cache folder and asks the UI to open it in another app. The copy is tracked:
     * when the user comes back and it changed, [syncEdits] puts it back on the NAS.
     */
    fun open(entry: RemoteEntry, edit: Boolean) = viewModelScope.launch {
        val id = UUID.randomUUID().toString()
        val dir = File(getApplication<Application>().cacheDir, "edit/$id")
        val local = File(dir, entry.name)
        try {
            val session = repo.io { fs ->
                dir.mkdirs()
                val sent = local.outputStream().use { out ->
                    Integrity.download(fs, entry.share, entry.path, out) { done, total ->
                        _transfer.value = Transfer("Apro ${entry.name}", done, total)
                    }
                }
                val stored = EditSync.sha256(local)
                if (stored != sent) throw Integrity.mismatch(entry.name, "la copia sul telefono è diversa")
                val remote = fs.stat(entry.share, entry.path) ?: entry
                EditSession(
                    id = id,
                    share = entry.share,
                    path = entry.path,
                    localFile = local,
                    baseHash = stored,
                    remoteSize = remote.size,
                    remoteModified = remote.modified,
                    openedAt = System.currentTimeMillis(),
                )
            }
            sessions.put(session)
            events.send(UiEvent.OpenFile(local, entry.name, edit))
        } catch (e: Exception) {
            dir.deleteRecursively()
            if (e is CancellationException) throw e
            message(e.readable())
        } finally {
            _transfer.value = null
        }
    }

    /** The UI could not find an app for the file: nothing will be edited, drop the copy. */
    fun openFailed(file: File) {
        sessions.all().firstOrNull { it.localFile == file }?.let { sessions.remove(it) }
        message("Nessuna app installata può aprire questo file")
    }

    /** Called whenever the app comes back to the foreground, typically after the editor closed. */
    fun syncEdits() {
        if (!repo.isConfigured || _syncPrompt.value != null) return
        viewModelScope.launch {
            syncMutex.withLock {
                for (session in sessions.all()) {
                    if (!session.localFile.exists()) {
                        sessions.remove(session)
                        continue
                    }
                    try {
                        val hash = withContext(Dispatchers.IO) { EditSync.sha256(session.localFile) }
                        val remote = repo.io { it.stat(session.share, session.path) }
                        when (EditSync.decide(session, hash, remote)) {
                            SyncDecision.Unchanged -> if (System.currentTimeMillis() - session.openedAt > KEEP_UNCHANGED_MS) {
                                sessions.remove(session)
                            }
                            SyncDecision.Upload -> {
                                uploadEdit(session, session.path, hash)
                                message("«${RemotePath.name(session.path)}» aggiornato sul NAS")
                            }
                            is SyncDecision.Conflict -> {
                                _syncPrompt.value = SyncPrompt(session, remoteMissing = false)
                                return@withLock
                            }
                            SyncDecision.RemoteMissing -> {
                                _syncPrompt.value = SyncPrompt(session, remoteMissing = true)
                                return@withLock
                            }
                        }
                    } catch (e: Exception) {
                        message("Non riesco a salvare «${RemotePath.name(session.path)}» sul NAS: ${e.readable()}")
                    }
                }
            }
        }
    }

    /** Conflict answer: replace the NAS version with the phone's. */
    fun resolveOverwrite() = resolve { session ->
        uploadEdit(session, session.path, null)
        message("«${RemotePath.name(session.path)}» sovrascritto sul NAS")
    }

    /** Conflict answer: keep both, the phone's version goes next to the original as a copy. */
    fun resolveKeepBoth() = resolve { session ->
        val copy = repo.io { EditSync.freeCopyPath(it, session.share, session.path, "dal telefono") }
        uploadEdit(session, copy, null)
        message("Salvato come «${RemotePath.name(copy)}»")
    }

    /** Conflict answer: throw the phone's changes away. */
    fun resolveDiscard() = resolve { session ->
        sessions.remove(session)
        message("Modifiche del telefono scartate")
    }

    private fun resolve(action: suspend (EditSession) -> Unit) {
        val prompt = _syncPrompt.value ?: return
        _syncPrompt.value = null
        viewModelScope.launch {
            try {
                action(prompt.session)
            } catch (e: Exception) {
                message(e.readable())
            }
            syncEdits()
        }
    }

    /** Uploads the edited copy to [target] and makes that the new agreed version of the session. */
    private suspend fun uploadEdit(session: EditSession, target: String, knownHash: String?) {
        val name = RemotePath.name(target)
        try {
            val hash = knownHash ?: withContext(Dispatchers.IO) { EditSync.sha256(session.localFile) }
            val result = repo.io { fs ->
                EditSync.safeOverwrite(fs, session.share, target, session.localFile) { done, total ->
                    _transfer.value = Transfer("Salvo $name sul NAS", done, total)
                }
            }
            sessions.put(
                session.copy(
                    path = target,
                    baseHash = hash,
                    remoteSize = result.size,
                    remoteModified = result.modified,
                    openedAt = System.currentTimeMillis(),
                )
            )
        } finally {
            _transfer.value = null
        }
        val s = _screen.value
        if (s is Screen.Browser && s.share == session.share && s.path == RemotePath.parent(target)) refresh()
    }

    // --- helpers ----------------------------------------------------------------------------------------------------

    private fun folderOperation(done: String, block: suspend (share: String, dir: String) -> Unit) {
        val s = _screen.value as? Screen.Browser ?: return
        viewModelScope.launch {
            try {
                block(s.share, s.path)
                message(done)
            } catch (e: Exception) {
                message(e.readable())
            }
            app.nasChanged.tryEmit(NasChange(s.share, s.path))
            refresh()
        }
    }

    private fun checkName(name: String): String {
        val n = name.trim()
        require(RemotePath.isValidName(n)) { "Nome non valido: non può contenere \\ / : * ? \" < > |" }
        return n
    }

    private fun message(text: String) {
        events.trySend(UiEvent.Message(text))
    }

    private companion object {
        /** Unchanged copies are forgotten after a week: the editor is long closed by then. */
        const val KEEP_UNCHANGED_MS = 7L * 24 * 60 * 60 * 1000
    }
}
