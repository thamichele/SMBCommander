package io.github.thamichele.smbcommander.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.EditSession
import io.github.thamichele.smbcommander.core.EditSync
import io.github.thamichele.smbcommander.core.NasServer
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.core.SyncDecision
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.data.isImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.File
import java.util.UUID

sealed interface Screen {
    data object Setup : Screen
    data object Shares : Screen
    data object Backup : Screen
    data class Browser(val share: String, val path: String) : Screen

    data object Gallery : Screen

    /** Full-screen photos, swiping through [images] starting at [index]; back returns to [returnTo]. */
    data class Viewer(val returnTo: Screen, val images: List<RemoteEntry>, val index: Int) : Screen
}

enum class SortMode { Name, Date, Size }

data class Transfer(val label: String, val done: Long, val total: Long) {
    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
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
    /** What the list shows: hidden and system entries filtered out, search applied, folders first. */
    val visible: List<RemoteEntry>
        get() {
            val filtered = entries.filter { e ->
                (showHidden || !(e.name.startsWith(".") || e.name.startsWith("@"))) &&
                    (query.isBlank() || e.name.contains(query.trim(), ignoreCase = true))
            }
            val order = when (sort) {
                SortMode.Name -> compareBy<RemoteEntry> { it.name.lowercase() }
                SortMode.Date -> compareByDescending { it.modified }
                SortMode.Size -> compareByDescending<RemoteEntry> { it.size }.thenBy { it.name.lowercase() }
            }
            return filtered.sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.then(order))
        }
}

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

    private val _browser = MutableStateFlow(BrowserState())
    val browser: StateFlow<BrowserState> = _browser.asStateFlow()

    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer.asStateFlow()

    private val _syncPrompt = MutableStateFlow<SyncPrompt?>(null)
    val syncPrompt: StateFlow<SyncPrompt?> = _syncPrompt.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val events = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvents = events.receiveAsFlow()

    /** Listings already seen: going back shows them at once while a fresh copy loads. */
    private val listingCache = HashMap<String, List<RemoteEntry>>()
    private var listJob: Job? = null
    private val syncMutex = Mutex()

    fun savedServer(): NasServer? = repo.server()

    // --- connection -------------------------------------------------------------------------------------------------

    fun connect(server: NasServer) = viewModelScope.launch {
        _connecting.value = true
        try {
            repo.connect(server)
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
        io.github.thamichele.smbcommander.backup.BackupScheduler.apply(app, app.backupSettings.load())
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

    // --- navigation -------------------------------------------------------------------------------------------------

    fun openFolder(entry: RemoteEntry) = navigate(entry.share, entry.path)

    fun navigate(share: String, path: String) {
        _screen.value = Screen.Browser(share, RemotePath.normalize(path))
        _browser.update { it.copy(query = "", searching = false) }
        refresh()
    }

    /** Opens the photo viewer on [entry], with the other images of the folder as they are shown in the list. */
    fun openViewer(entry: RemoteEntry) {
        val s = _screen.value as? Screen.Browser ?: return
        val images = _browser.value.visible.filter { it.isImage }
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
                if (_browser.value.searching) {
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
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (_screen.value == s) _browser.update { it.copy(loading = false, error = e.readable()) }
            }
        }
    }

    fun setSort(sort: SortMode) = _browser.update { it.copy(sort = sort) }
    fun setQuery(query: String) = _browser.update { it.copy(query = query) }
    fun setSearching(on: Boolean) = _browser.update { it.copy(searching = on, query = if (on) it.query else "") }
    fun toggleHidden() = _browser.update { it.copy(showHidden = !it.showHidden) }
    fun toggleGrid() = _browser.update { it.copy(grid = !it.grid) }

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

    fun delete(entry: RemoteEntry) = folderOperation("«${entry.name}» eliminato") { share, _ ->
        repo.io { it.delete(share, entry.path) }
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
                    val (name, size) = withContext(Dispatchers.IO) { describe(uri) }
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
                refresh()
            }
        }
    }

    /** Saves a NAS file into a document the user created with the system picker. */
    fun downloadTo(entry: RemoteEntry, uri: Uri) = viewModelScope.launch {
        val resolver = getApplication<Application>().contentResolver
        try {
            repo.io { fs ->
                (resolver.openOutputStream(uri) ?: error("Impossibile scrivere il file")).use { out ->
                    fs.download(entry.share, entry.path, out) { done, total ->
                        _transfer.value = Transfer("Scarico ${entry.name}", done, total)
                    }
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
                local.outputStream().use { out ->
                    fs.download(entry.share, entry.path, out) { done, total ->
                        _transfer.value = Transfer("Apro ${entry.name}", done, total)
                    }
                }
                val remote = fs.stat(entry.share, entry.path) ?: entry
                EditSession(
                    id = id,
                    share = entry.share,
                    path = entry.path,
                    localFile = local,
                    baseHash = EditSync.sha256(local),
                    remoteSize = remote.size,
                    remoteModified = remote.modified,
                    openedAt = System.currentTimeMillis(),
                )
            }
            sessions.put(session)
            events.send(UiEvent.OpenFile(local, entry.name, edit))
        } catch (e: Exception) {
            dir.deleteRecursively()
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
                        when (val decision = EditSync.decide(session, hash, remote)) {
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
            refresh()
        }
    }

    private fun checkName(name: String): String {
        val n = name.trim()
        require(RemotePath.isValidName(n)) { "Nome non valido: non può contenere \\ / : * ? \" < > |" }
        return n
    }

    private fun describe(uri: Uri): Pair<String, Long> {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.takeIf { RemotePath.isValidName(it) } ?: "file"
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                return name to size
            }
        }
        return (uri.lastPathSegment?.substringAfterLast('/')?.takeIf { RemotePath.isValidName(it) } ?: "file") to -1L
    }

    private fun message(text: String) {
        events.trySend(UiEvent.Message(text))
    }

    private fun Throwable.readable(): String = when (this) {
        is IllegalArgumentException, is IllegalStateException -> message ?: "Errore"
        is io.github.thamichele.smbcommander.core.NasException -> message ?: "Errore del NAS"
        is com.hierynomus.mssmb2.SMBApiException -> when (status) {
            com.hierynomus.mserref.NtStatus.STATUS_ACCESS_DENIED -> "Permesso negato dal NAS"
            com.hierynomus.mserref.NtStatus.STATUS_OBJECT_NAME_COLLISION -> "Esiste già un elemento con questo nome"
            com.hierynomus.mserref.NtStatus.STATUS_SHARING_VIOLATION -> "Il file è in uso da un altro programma"
            com.hierynomus.mserref.NtStatus.STATUS_DISK_FULL -> "Spazio esaurito sul NAS"
            com.hierynomus.mserref.NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            com.hierynomus.mserref.NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "Elemento non trovato sul NAS"
            else -> "Errore del NAS: $status"
        }
        else -> message ?: javaClass.simpleName
    }

    private companion object {
        /** Unchanged copies are forgotten after a week: the editor is long closed by then. */
        const val KEEP_UNCHANGED_MS = 7L * 24 * 60 * 60 * 1000
    }
}
