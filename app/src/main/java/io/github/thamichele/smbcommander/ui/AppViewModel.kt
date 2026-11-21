/*
 * AppViewModel.kt - state and actions of the file browser and of everything shared by the screens
 *
 * Navigation, folders, file operations, editing, transfers, the bin and the settings. The NAS work itself is done by
 * the core module and by the stores of SmbCommanderApp.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.ui

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thamichele.smbcommander.NasChange
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.backup.BackupScheduler
import io.github.thamichele.smbcommander.core.Conflict
import io.github.thamichele.smbcommander.core.DeletedPhotos
import io.github.thamichele.smbcommander.core.EditSession
import io.github.thamichele.smbcommander.core.EditSync
import io.github.thamichele.smbcommander.core.FileTrash
import io.github.thamichele.smbcommander.core.RandomReader
import io.github.thamichele.smbcommander.core.ZipArchive
import io.github.thamichele.smbcommander.core.ZipExtract
import io.github.thamichele.smbcommander.core.FolderScan
import io.github.thamichele.smbcommander.core.Integrity
import io.github.thamichele.smbcommander.core.MultiNasFileSystem
import io.github.thamichele.smbcommander.core.NasServer
import io.github.thamichele.smbcommander.core.NasShares
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.core.SyncDecision
import io.github.thamichele.smbcommander.core.TransferLog
import io.github.thamichele.smbcommander.core.Transfers
import io.github.thamichele.smbcommander.core.unlessGone
import io.github.thamichele.smbcommander.data.OtherNas
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.data.FoundNas
import io.github.thamichele.smbcommander.data.MusicState
import io.github.thamichele.smbcommander.data.NasFinder
import io.github.thamichele.smbcommander.data.WakeOnLan
import io.github.thamichele.smbcommander.data.PhoneUploads
import io.github.thamichele.smbcommander.data.isAudio
import io.github.thamichele.smbcommander.data.ShareCache
import io.github.thamichele.smbcommander.data.TransferJob
import io.github.thamichele.smbcommander.data.isMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    /** A ZIP archive on the NAS opened like a folder, at [dir] inside it; back goes up, then to [returnTo]. */
    data class Archive(val returnTo: Browser, val entry: RemoteEntry, val dir: String) : Screen

    /** The bin of the file browser, every share together; back returns to [returnTo]. */
    data class Trash(val returnTo: Screen) : Screen

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

    /**
     * message() - the outcome of a deletion of a single file, for a short message
     * @param done the text when the file was deleted
     *
     * Return: [done], or why the file was skipped or not deleted; without steps the error, or [done].
     */
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
    /** Search also in the subfolders: the results come from [deepResults], not from [entries]. */
    val deep: Boolean = false,
    val deepResults: List<RemoteEntry> = emptyList(),
    /** The search in the subfolders is still walking; [deepFolder] is the folder it is reading. */
    val deepBusy: Boolean = false,
    val deepFolder: String = "",
) {
    /** True while the list shows matches from every level under the folder. */
    val showingDeep: Boolean get() = searching && deep && query.isNotBlank()

    /** What the list shows: hidden and system entries filtered out, search applied, folders first. Worked out once. */
    val visible: List<RemoteEntry> by lazy {
        if (showingDeep) return@lazy deepResults
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

/** The optional functions as the settings show them. */
data class ExtrasState(
    val lock: Boolean,
    val wakeOnLan: Boolean,
    val wakeMac: String,
    val followNas: Boolean,
    /** A video opened from a list starts by itself. */
    val autoplayVideo: Boolean,
    /** Each folder of File shows in small the NAS it is on and the user. */
    val showNasOnFolders: Boolean = false,
)

/** The folder of a ZIP archive being shown. */
data class ArchiveState(
    val items: List<ZipArchive.Entry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** The bin of the file browser as the screen shows it. */
data class TrashState(
    val items: List<FileTrash.Item> = emptyList(),
    val loading: Boolean = true,
    val working: Boolean = false,
    val error: String? = null,
)

/** Some of the elements about to go into a folder are already there: [go] runs with the user's choice. */
data class ConflictAsk(val names: List<String>, val total: Int, val go: (Conflict) -> Unit)

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    /** A message with a button, such as "Annulla" after moving something to the bin. */
    data class MessageWithAction(val text: String, val action: String, val onAction: () -> Unit) : UiEvent
    data class OpenFile(val file: File, val name: String, val edit: Boolean) : UiEvent
    /** Temporary copies ready for the system's share sheet. */
    data class Share(val files: List<File>) : UiEvent
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
                else if (job.state == TransferJob.State.Done && job.note != null) message("${job.label}: ${job.note}")
            }
        }
    }

    /**
     * savedServer() - the main NAS as saved on this phone
     *
     * Context: decrypts the password: not for lists or anything redrawn often.
     * Return: the saved NAS, or null before the first connection.
     */
    fun savedServer(): NasServer? = repo.server()

    /**
     * savedUser() - the user of the main NAS, for the lists
     *
     * Cheap, where [savedServer] decrypts the password.
     *
     * Return: the user name, empty when none is saved.
     */
    fun savedUser(): String = repo.user()

    /**
     * savedHost() - the address of the main NAS, for the lists
     *
     * Return: the address, empty when none is saved.
     */
    fun savedHost(): String = repo.host().orEmpty()

    // --- connection -------------------------------------------------------------------------------------------------

    /**
     * connect() - connects to a NAS and saves it once it answers
     * @param server address, user, password and domain typed by the user
     *
     * An empty password keeps the saved one, as long as address and user did not change.
     *
     * Context: launches a coroutine; the outcome arrives as a message and a new screen.
     * Return: the job of the connection.
     */
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

    /**
     * disconnect() - forgets the NAS and everything of it on this phone
     *
     * The song playing, the transfers and the clipboard stop; folders, backup settings, edited copies, thumbnails and
     * previews are deleted. Nothing changes on the NAS.
     *
     * Return: nothing.
     */
    fun disconnect() {
        // nothing keeps reading or writing the NAS just forgotten: the song playing and the transfers stop
        app.music.stop()
        app.transfers.cancelAll()
        _clipboard.value = null
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
            // copies written by other apps through the file picker that never reached the NAS
            File(cache, "documents").deleteRecursively()
            app.shareCache.cleanAll()
            File(cache, "images").deleteRecursively()
            File(getApplication<Application>().filesDir, "gallery").deleteRecursively()
            app.thumbnails.ratios.clear()
            app.photoSorter.clear()
        }
        listingCache.clear()
        _folders.value = emptyList()
        _otherNas.value = emptyList()
        _screen.value = Screen.Setup
    }

    /** True while someone asked for the backup page itself (notification, "Vai al backup foto"), not the list. */
    val backupWanted: StateFlow<Boolean> get() = _backupWanted
    private val _backupWanted = MutableStateFlow(false)

    /**
     * openBackup() - opens the settings straight on the "Backup foto" page
     *
     * Return: nothing.
     */
    fun openBackup() {
        _backupWanted.value = true
        _screen.value = Screen.Backup
    }

    /**
     * backupShown() - the settings have opened the backup page asked with [openBackup]
     *
     * Return: nothing.
     */
    fun backupShown() {
        _backupWanted.value = false
    }

    /**
     * openGallery() - opens the Gallery section
     *
     * Return: nothing.
     */
    fun openGallery() {
        _screen.value = Screen.Gallery
    }

    /**
     * openCleanup() - opens the quick cleanup of the gallery
     *
     * Return: nothing.
     */
    fun openCleanup() {
        _screen.value = Screen.Cleanup
    }

    /**
     * openFiles() - opens the File section, on the list of the user's folders
     *
     * Return: nothing.
     */
    fun openFiles() {
        _screen.value = Screen.Shares
    }

    /**
     * openSettings() - opens the settings on the list of their menus
     *
     * Return: nothing.
     */
    fun openSettings() {
        // the settings are the third section of the bottom bar; Screen.Setup is only the first connection
        _backupWanted.value = false
        _screen.value = Screen.Backup
    }

    // --- the user's folders ------------------------------------------------------------------------------------------

    /**
     * loadSuggestions() - lists the shares the user can add as folders
     * @param nasId null for the shares of the main NAS, otherwise the other NAS whose shares are listed by their own names
     *
     * Context: launches a coroutine; the list arrives in [suggestions].
     * Return: the job of the listing.
     */
    fun loadSuggestions(nasId: String? = null) = viewModelScope.launch {
        _suggestions.value = emptyList()
        _suggestions.value = runCatching {
            repo.io { fs ->
                if (nasId == null) fs.listShares()
                else fs.list(NasShares.root(nasId), "").map { NasShares.localOf(it.share) }
            }
        }.getOrDefault(emptyList())
    }

    /**
     * addFolder() - adds a folder typed by the user after checking it really opens
     * @param input what the user typed ("Multimedia/Foto", "Backup$")
     * @param nasId the other NAS it is on, null for the main one
     * @param onAdded called once the folder is saved
     *
     * Context: the check runs in a coroutine; failures arrive as a message.
     * Return: nothing.
     */
    fun addFolder(input: String, nasId: String? = null, onAdded: () -> Unit) {
        val typed = SavedFolder.parse(input) ?: return message("Scrivi il nome di una cartella condivisa")
        val folder = if (nasId == null) typed else typed.copy(share = NasShares.of(nasId, typed.share))
        viewModelScope.launch {
            _checkingFolder.value = true
            try {
                val entry = repo.io { fs ->
                    fs.list(folder.share, folder.path)
                    if (folder.path.isEmpty()) null else fs.stat(folder.share, folder.path)
                }
                if (entry != null && !entry.isDirectory) error("«${folder.shown}» è un file, non una cartella")
                _folders.value = app.folders.add(folder)
                onAdded()
                message("«${folder.name}» aggiunta alle tue cartelle")
            } catch (e: Exception) {
                message("Non riesco ad aprire «${folder.shown}»: ${e.readable()}")
            } finally {
                _checkingFolder.value = false
            }
        }
    }

    /**
     * removeFolder() - takes a folder off the list of the user's folders
     * @param folder the folder
     *
     * Nothing changes on the NAS.
     *
     * Return: nothing.
     */
    fun removeFolder(folder: SavedFolder) {
        _folders.value = app.folders.remove(folder)
        message("«${folder.name}» tolta dall'elenco (sul NAS non cambia nulla)")
    }

    // --- the other NAS ----------------------------------------------------------------------------------------------

    private val _otherNas = MutableStateFlow(repo.otherNas())
    /** The NAS added next to the main one; each shows as one more folder in File. */
    val otherNas: StateFlow<List<OtherNas>> = _otherNas.asStateFlow()

    private val _savingNas = MutableStateFlow(false)
    val savingNas: StateFlow<Boolean> = _savingNas.asStateFlow()

    /**
     * saveOtherNas() - adds another NAS, or changes one, after really connecting to it
     * @param id the NAS to change, or null for a new one
     * @param name the name of its folder; the address is used when empty
     * @param server address and credentials; an empty password keeps the saved one
     * @param onSaved called once the NAS answered and was saved
     *
     * A new NAS gets its folder in File right away, at the bottom of the list.
     *
     * Return: nothing.
     */
    fun saveOtherNas(id: String?, name: String, server: NasServer, onSaved: () -> Unit) {
        if (_savingNas.value) return
        viewModelScope.launch {
            _savingNas.value = true
            try {
                val nas = repo.saveOther(id, name.trim().ifEmpty { server.host }, server)
                if (id == null) _folders.value = app.folders.add(SavedFolder(NasShares.root(nas.id), ""))
                // a new name shows on the screens that collect this list
                _otherNas.value = repo.otherNas()
                listingCache.clear()
                onSaved()
                message(if (id == null) "«${nas.name}» aggiunto alle tue cartelle" else "«${nas.name}» salvato")
            } catch (e: Exception) {
                message("Non riesco a collegarmi: ${e.readable()}")
            } finally {
                _savingNas.value = false
            }
        }
    }

    /**
     * removeOtherNas() - forgets another NAS and all its folders on this phone
     * @param nas the other NAS
     *
     * Nothing changes on the NAS itself.
     *
     * Return: nothing.
     */
    fun removeOtherNas(nas: OtherNas) {
        repo.removeOther(nas.id)
        _otherNas.value = repo.otherNas()
        _folders.value = app.folders.removeNas(nas.id)
        listingCache.clear()
        _clipboard.update { clip -> clip?.takeIf { c -> c.entries.none { NasShares.nasOf(it.share) == nas.id } } }
        val here = _screen.value
        if (here is Screen.Browser && NasShares.nasOf(here.share) == nas.id) _screen.value = Screen.Shares
        message("«${nas.name}» tolto da questo telefono (sul NAS non cambia nulla)")
    }

    /**
     * pinCurrent() - adds the folder being browsed to the user's folders
     *
     * Return: nothing.
     */
    fun pinCurrent() {
        val s = _screen.value as? Screen.Browser ?: return
        val folder = SavedFolder(s.share, s.path)
        _folders.value = app.folders.add(folder)
        message("«${folder.name}» aggiunta alle tue cartelle")
    }

    /**
     * openSaved() - opens one of the user's folders
     * @param folder the folder
     *
     * Return: nothing.
     */
    fun openSaved(folder: SavedFolder) = navigate(folder.share, folder.path)

    /**
     * openFromOutside() - opens a folder asked for from a widget
     * @param share the share of the folder
     * @param path its path in the share
     *
     * The intent can come from any app, so only one of the user's folders (or a folder inside one) is opened.
     *
     * Return: nothing.
     */
    fun openFromOutside(share: String, path: String) {
        if (!repo.isConfigured) return
        val dir = RemotePath.normalize(path)
        val allowed = app.folders.all().any { it.contains(share, dir) }
        if (allowed) navigate(share, dir)
    }

    // --- navigation -------------------------------------------------------------------------------------------------

    /* Where each folder was scrolled to, so that going back finds the list as it was left. */
    private val scrollMemory = HashMap<Screen.Browser, Pair<Int, Int>>()

    /**
     * openFolder() - opens a folder of the listing
     * @param entry the folder
     *
     * Return: nothing.
     */
    fun openFolder(entry: RemoteEntry) = navigate(entry.share, entry.path)

    /**
     * scrollOf() - where the list of a folder was left
     * @param screen the folder
     *
     * Return: the first item shown and its offset in pixels; 0, 0 for a folder never scrolled.
     */
    fun scrollOf(screen: Screen.Browser): Pair<Int, Int> = scrollMemory[screen] ?: (0 to 0)

    /**
     * keepScroll() - remembers where the list of a folder is, when the user leaves it
     * @param screen the folder
     * @param index first item shown
     * @param offset how far that item is scrolled, in pixels
     *
     * Return: nothing.
     */
    fun keepScroll(screen: Screen.Browser, index: Int, offset: Int) {
        if (index == 0 && offset == 0) {
            scrollMemory.remove(screen)
            return
        }
        if (scrollMemory.size >= SCROLL_MEMORY_MAX) scrollMemory.clear()
        scrollMemory[screen] = index to offset
    }

    /**
     * navigate() - shows a folder of the NAS and reads its content
     * @param share the share
     * @param path the folder in the share, empty for the share itself
     *
     * Return: nothing.
     */
    fun navigate(share: String, path: String) {
        _selected.value = emptySet()
        _screen.value = Screen.Browser(share, RemotePath.normalize(path))
        _browser.update { it.copy(query = "", searching = false) }
        refresh()
    }

    /**
     * openViewer() - opens the viewer on a file of the folder being browsed
     * @param entry the photo or video
     *
     * The other photos and videos of the folder follow, as they are shown in the list.
     *
     * Return: nothing.
     */
    fun openViewer(entry: RemoteEntry) {
        val s = _screen.value as? Screen.Browser ?: return
        val images = _browser.value.visible.filter { it.isMedia }
        _screen.value = Screen.Viewer(s, images, images.indexOf(entry).coerceAtLeast(0))
    }

    /**
     * openViewer() - opens the viewer on a list of its own, as the gallery does
     * @param images the photos and videos, in their order
     * @param index the one shown first
     *
     * Return: nothing.
     */
    fun openViewer(images: List<RemoteEntry>, index: Int) {
        if (images.isEmpty()) return
        _screen.value = Screen.Viewer(_screen.value, images, index.coerceIn(0, images.lastIndex))
    }

    /**
     * back() - goes back one step: a selection, a search, the folder above, the previous screen
     *
     * The way up follows the full path of the folder, one level at a time, up to the user's folders.
     *
     * Return: false when there is nothing left to go back to (the activity then closes).
     */
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
                    setSearching(false)
                } else {
                    // always the folder above in the absolute path, up to the share (or the other NAS), then home
                    val up = SavedFolder(s.share, s.path).parent()
                    if (up != null) {
                        navigate(up.share, up.path)
                    } else {
                        listJob?.cancel()
                        _screen.value = Screen.Shares
                    }
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
            is Screen.Archive -> {
                if (s.dir.isEmpty()) {
                    closeArchive()
                    _screen.value = s.returnTo
                    refresh()
                } else {
                    _screen.value = s.copy(dir = RemotePath.parent(s.dir))
                    showArchiveDir()
                }
                return true
            }
            is Screen.Trash -> {
                _screen.value = s.returnTo
                if (s.returnTo is Screen.Browser) refresh()
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

    /**
     * refresh() - reads the folder being browsed again
     *
     * The copy already read stays on screen until the new one arrives.
     *
     * Return: nothing.
     */
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
                followIfMoved(e)
            }
        }
    }

    /**
     * setSort() - changes and saves the order of the listings
     * @param sort the new order
     *
     * Return: nothing.
     */
    fun setSort(sort: SortMode) {
        browserPrefs.sort = sort
        _browser.update { it.copy(sort = sort) }
    }

    /**
     * setQuery() - filters the listing by name
     * @param query what the user typed
     *
     * A search of the subfolders starts again with it.
     *
     * Return: nothing.
     */
    fun setQuery(query: String) {
        _browser.update { it.copy(query = query) }
        if (_browser.value.deep) startDeepSearch()
    }

    /**
     * setSearching() - opens or closes the search field
     * @param on true opens it; closing it forgets the query and stops a search of the subfolders
     *
     * Return: nothing.
     */
    fun setSearching(on: Boolean) {
        if (!on) deepJob?.cancel()
        _browser.update { it.copy(searching = on, query = if (on) it.query else "", deepResults = if (on) it.deepResults else emptyList(), deepBusy = false) }
    }

    /**
     * setDeepSearch() - switches the search between this folder only and every subfolder too
     * @param on true searches the subfolders
     *
     * Return: nothing.
     */
    fun setDeepSearch(on: Boolean) {
        _browser.update { it.copy(deep = on) }
        if (on) startDeepSearch() else {
            deepJob?.cancel()
            _browser.update { it.copy(deepResults = emptyList(), deepBusy = false) }
        }
    }

    private var deepJob: Job? = null

    /** Walks every level under the folder for names with the query; results appear while it walks. */
    private fun startDeepSearch() {
        deepJob?.cancel()
        val s = _screen.value as? Screen.Browser ?: return
        val query = _browser.value.query.trim()
        // the folder of another NAS holds whole shares: searching all of them would walk entire disks
        if (query.length < 2 || NasShares.isRoot(s.share)) {
            _browser.update { it.copy(deepResults = emptyList(), deepBusy = false) }
            return
        }
        val hidden = _browser.value.showHidden
        _browser.update { it.copy(deepResults = emptyList(), deepBusy = true, deepFolder = "") }
        deepJob = viewModelScope.launch {
            delay(350) // typing: wait for a pause before walking the NAS
            val found = java.util.Collections.synchronizedList(ArrayList<RemoteEntry>())
            var folder = ""
            val publisher = launch {
                while (true) {
                    delay(250)
                    val snapshot = synchronized(found) { found.toList() }
                    _browser.update { if (it.query.trim() == query) it.copy(deepResults = snapshot, deepFolder = folder) else it }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    val fs = repo.fs()
                    FolderScan.search(
                        fs, s.share, s.path, query,
                        includeHidden = hidden,
                        stopped = { !isActive },
                        onFolder = { folder = it },
                    ) { found += it }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message(e.readable())
            } finally {
                publisher.cancel()
                val snapshot = synchronized(found) { found.toList() }
                _browser.update { if (it.query.trim() == query) it.copy(deepResults = snapshot, deepBusy = false) else it }
            }
        }
    }
    /**
     * toggleHidden() - shows or hides the hidden files, and saves the choice
     *
     * Return: nothing.
     */
    fun toggleHidden() = _browser.update { it.copy(showHidden = !it.showHidden).also { s -> browserPrefs.showHidden = s.showHidden } }
    /**
     * toggleGrid() - switches the listing between list and grid, and saves the choice
     *
     * Return: nothing.
     */
    fun toggleGrid() = _browser.update { it.copy(grid = !it.grid).also { s -> browserPrefs.grid = s.grid } }

    // --- file operations --------------------------------------------------------------------------------------------

    /**
     * createFolder() - creates a folder in the folder being browsed
     * @param name its name
     *
     * Context: launches a coroutine; the outcome arrives as a message.
     * Return: nothing.
     */
    fun createFolder(name: String) = folderOperation("Cartella creata") { share, dir ->
        val path = RemotePath.child(dir, checkName(name))
        repo.io {
            if (it.stat(share, path) != null) error("Esiste già un elemento chiamato «$name»")
            it.createDirectory(share, path)
        }
    }

    /**
     * rename() - renames a file or folder in place
     * @param entry the file or folder
     * @param newName the new name
     *
     * Context: launches a coroutine; the outcome arrives as a message.
     * Return: nothing.
     */
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

    /**
     * createTextFile() - creates an empty text file in the folder being browsed
     * @param name its name
     *
     * Editing it is left to the apps on the phone.
     *
     * Context: launches a coroutine; the outcome arrives as a message.
     * Return: nothing.
     */
    fun createTextFile(name: String) = folderOperation("File creato") { share, dir ->
        val clean = checkName(name).let { if (RemotePath.extension(it).isEmpty()) "$it.txt" else it }
        val path = RemotePath.child(dir, clean)
        repo.io {
            if (it.stat(share, path) != null) error("Esiste già un elemento chiamato «$clean»")
            it.upload(share, path, ByteArrayInputStream(ByteArray(0)), 0)
        }
    }

    // --- selection, copy and move -----------------------------------------------------------------------------------

    /**
     * toggleSelected() - adds a file to the selection, or takes it out
     * @param entry the file or folder
     *
     * Nothing can be selected in the folder of a whole NAS.
     *
     * Return: nothing.
     */
    fun toggleSelected(entry: RemoteEntry) {
        if (inNasRoot()) return
        _selected.update { if (entry.path in it) it - entry.path else it + entry.path }
    }

    /* The folder of another NAS lists its shares, all with the same empty path: nothing there can be selected. */
    private fun inNasRoot(): Boolean = (_screen.value as? Screen.Browser)?.let { NasShares.isRoot(it.share) } == true

    /**
     * clearSelection() - empties the selection
     *
     * Return: nothing.
     */
    fun clearSelection() {
        _selected.value = emptySet()
    }

    /**
     * selectAll() - selects everything the listing shows
     *
     * Return: nothing.
     */
    fun selectAll() {
        if (inNasRoot()) return
        _selected.value = _browser.value.visible.mapTo(HashSet()) { it.path }
    }

    private fun selectedEntries(): List<RemoteEntry> {
        val paths = _selected.value
        val b = _browser.value
        return (if (b.showingDeep) b.deepResults else b.entries).filter { it.path in paths }
    }

    /**
     * cut() - puts the selection on the clipboard
     * @param move true moves the files, false copies them
     *
     * It is pasted in whichever folder the user opens next.
     *
     * Return: nothing.
     */
    fun cut(move: Boolean) {
        val entries = selectedEntries()
        if (entries.isEmpty() || refusedAtNasRoot()) return
        _clipboard.value = Clipboard(entries, move)
        _selected.value = emptySet()
        message("Apri la cartella di destinazione e premi «Incolla qui»")
    }

    /**
     * clearClipboard() - forgets what was cut or copied
     *
     * Return: nothing.
     */
    fun clearClipboard() {
        _clipboard.value = null
    }

    private val _conflictAsk = MutableStateFlow<ConflictAsk?>(null)
    /** Names already in the destination: the user chooses whether to replace, skip or keep both. */
    val conflictAsk: StateFlow<ConflictAsk?> = _conflictAsk.asStateFlow()

    /**
     * answerConflict() - answers the question about names already in the destination
     * @param choice replace, skip or keep both; null closes it without doing anything
     *
     * Return: nothing.
     */
    fun answerConflict(choice: Conflict?) {
        val ask = _conflictAsk.value ?: return
        _conflictAsk.value = null
        if (choice != null) ask.go(choice)
    }

    /**
     * Runs [go] at once when none of [names] is in the folder being browsed, else asks first. The listing on screen
     * is enough to know: the NAS compares names without regard to case, and so does this.
     */
    private fun askIfTaken(names: List<String>, go: (Conflict) -> Unit) {
        val taken = _browser.value.entries.mapTo(HashSet()) { it.name.lowercase() }
        val clashes = names.filter { it.lowercase() in taken }
        if (clashes.isEmpty()) go(Conflict.KeepBoth) else _conflictAsk.value = ConflictAsk(clashes, names.size, go)
    }

    /**
     * paste() - queues one transfer per clipboard entry, into the folder being browsed
     *
     * Names already there are asked about first.
     *
     * Return: nothing.
     */
    fun paste() {
        val s = _screen.value as? Screen.Browser ?: return
        val clip = _clipboard.value ?: return
        if (refusedAtNasRoot()) return
        val destination = if (s.path.isEmpty()) NasShares.shownName(s.share) else RemotePath.name(s.path)
        // what is already in this very folder is not a clash: a copy there is always a new copy, a move does nothing
        val here = { e: RemoteEntry -> e.share == s.share && RemotePath.parent(e.path) == RemotePath.normalize(s.path) }
        askIfTaken(clip.entries.filterNot(here).map { it.name }) { conflict ->
            for (entry in clip.entries) {
                val label = (if (clip.move) "Sposta" else "Copia") + " «${entry.name}» in $destination"
                val log = TransferLog()
                app.transfers.enqueue(label) { fs, progress ->
                    val listener = Transfers.Listener { name, done, total -> progress(name, done, total) }
                    if (clip.move) Transfers.move(fs, entry, s.share, s.path, listener, conflict, log)
                    else Transfers.copy(fs, entry, s.share, s.path, listener, conflict, log)
                    when (log.skipped) {
                        0 -> null
                        1 -> "1 file già presente, lasciato com'era"
                        else -> "${log.skipped} file già presenti, lasciati com'erano"
                    }
                }
            }
            _clipboard.value = null
        }
    }

    /**
     * cancelTransfers() - stops every transfer, the running one and those waiting
     *
     * Return: nothing.
     */
    fun cancelTransfers() = app.transfers.cancelAll()

    /**
     * pauseTransfers() - pauses the transfers; each one resumes where it stopped
     *
     * Return: nothing.
     */
    fun pauseTransfers() = app.transfers.pause()

    /**
     * resumeTransfers() - lets the paused transfers run again
     *
     * Return: nothing.
     */
    fun resumeTransfers() = app.transfers.resume()

    /**
     * retryTransfer() - runs a failed transfer again
     * @param id the transfer
     *
     * Return: nothing.
     */
    fun retryTransfer(id: Long) = app.transfers.retry(id)

    val transfersPaused: StateFlow<Boolean> = app.transfers.paused

    /**
     * clearTransfers() - forgets the transfers that ended
     *
     * Return: nothing.
     */
    fun clearTransfers() = app.transfers.clearFinished()

    /**
     * deleteSelected() - deletes the selected files and folders, into the bin of the share
     *
     * Return: nothing.
     */
    fun deleteSelected() {
        val entries = selectedEntries()
        _selected.value = emptySet()
        deleteInBrowser(entries)
    }

    /**
     * delete() - deletes one file or folder, into the bin of the share
     * @param entry the file or folder
     *
     * Return: nothing.
     */
    fun delete(entry: RemoteEntry) = deleteInBrowser(listOf(entry))

    private fun deleteInBrowser(entries: List<RemoteEntry>) {
        val s = _screen.value as? Screen.Browser ?: return
        if (refusedAtNasRoot()) return
        viewModelScope.launch {
            try {
                // the bin itself, and shares as a whole, are deleted for good
                val toTrash = entries.none { it.path.isEmpty() || FileTrash.isInside(it.path) }
                val result = deleteFromNas(entries, toTrash)
                val trashed = lastTrashed
                if (toTrash && trashed.isNotEmpty()) {
                    val text = if (trashed.size == 1) "«${trashed[0].entry.name}» spostato nel cestino" else "${trashed.size} elementi spostati nel cestino"
                    events.trySend(UiEvent.MessageWithAction(text, "Annulla") { restoreFromTrash(trashed) })
                } else if (entries.size == 1) {
                    message(result.message("«${entries[0].name}» eliminato"))
                }
            } catch (e: Exception) {
                // with more than one file the deletion window shows what went wrong
                if (entries.size == 1) message(e.readable())
            }
            app.nasChanged.tryEmit(NasChange(s.share, s.path))
            refresh()
        }
    }

    /**
     * deletePhotos() - deletes photos from the NAS, from the gallery or the viewer
     * @param photos the photos and videos
     *
     * Those in the backup folder are first written to its list of deleted photos (size and hash), so the backup does not
     * upload them again, even after a reinstall.
     *
     * Context: launches a coroutine; the progress shows in [deletion].
     * Return: the job of the deletion.
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

    private val deletions = DeletionProgress()

    /** A deletion of several files under way, or just finished: shown file by file in a window of its own. */
    val deletion: StateFlow<Deletion?> = deletions.state

    /**
     * stopDeletion() - stops the deletion after the file being deleted now; the others stay where they are
     *
     * Return: nothing.
     */
    fun stopDeletion() = deletions.stop()

    /**
     * closeDeletion() - closes the window of the deletion once it has finished
     *
     * Return: nothing.
     */
    fun closeDeletion() = deletions.close()

    /** What the last deletion of the browser moved to the bin, for its "Annulla". */
    private var lastTrashed: List<FileTrash.Item> = emptyList()

    /*
     * Deletes [entries] from the NAS one by one; with more than one, each step is shown by [deletion]. One that is no
     * longer there (deleted or moved from another device in the meantime) is skipped, and one that fails does not stop
     * the others. Those of the backup folder are written to its list of deleted photos first, and taken off it again
     * if they were skipped or not deleted: a photo moved elsewhere must not stay remembered as deleted.
     */
    private suspend fun deleteFromNas(entries: List<RemoteEntry>, toTrash: Boolean = false): Deletion {
        lastTrashed = emptyList()
        val trashed = ArrayList<FileTrash.Item>()
        val many = entries.size > 1
        deletions.start()
        var state = Deletion(total = entries.size, preparing = 0)
        fun publish(next: Deletion) {
            state = next
            if (many) deletions.show(next)
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
                if (deletions.isStopRequested) break
                publish(state.copy(current = entry))
                val outcome = DeletionProgress.step(entry) {
                    val done = repo.io { fs ->
                        fs.unlessGone(entry.share, entry.path) {
                            if (toTrash) trashed.add(FileTrash.discard(fs, entry)) else fs.delete(entry.share, entry.path)
                        }
                    }
                    done != null
                }
                publish(state.copy(steps = state.steps + outcome))
            }
        } finally {
            publish(state.copy(current = null, finished = true))
            lastTrashed = trashed
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

    /**
     * setDeleteWithoutConfirm() - saves whether a swipe in the gallery list deletes at once
     * @param on true deletes without asking
     *
     * Return: nothing.
     */
    fun setDeleteWithoutConfirm(on: Boolean) {
        app.galleryPrefs.deleteWithoutConfirm = on
        _deleteWithoutConfirm.value = on
    }

    /** Takes deleted photos out of the viewer and of the folder listings already loaded. */
    private fun dropDeleted(gone: List<RemoteEntry>) {
        val keys = gone.map { it.share + "/" + it.path }.toSet()
        val isGone = { e: RemoteEntry -> e.share + "/" + e.path in keys }
        listingCache.replaceAll { _, list -> list.filterNot(isGone) }
        _browser.update { it.copy(entries = it.entries.filterNot(isGone), deepResults = it.deepResults.filterNot(isGone)) }
        val s = _screen.value as? Screen.Viewer ?: return
        val left = s.images.filterNot(isGone)
        _screen.value = if (left.isEmpty()) s.returnTo else s.copy(images = left, index = s.index.coerceAtMost(left.lastIndex))
    }

    /**
     * upload() - queues documents picked on the phone for the folder being browsed
     * @param uris the documents
     *
     * Names already taken are asked about.
     *
     * Return: nothing.
     */
    fun upload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val items = withContext(Dispatchers.IO) { uris.map { PhoneUploads.file(resolver, it) } }
            queueUpload(items, if (items.size == 1) "Carica «${items[0].name}»" else "Carica ${items.size} file")
        }
    }

    /**
     * uploadFolder() - queues a whole folder of the phone, with its subfolders, for the folder being browsed
     * @param tree the folder picked, null when the user gave up
     *
     * Return: nothing.
     */
    fun uploadFolder(tree: Uri?) {
        tree ?: return
        viewModelScope.launch {
            val resolver = getApplication<Application>().contentResolver
            val item = try {
                withContext(Dispatchers.IO) { PhoneUploads.folder(resolver, tree) }
            } catch (e: Exception) {
                message("Impossibile leggere la cartella scelta")
                return@launch
            }
            queueUpload(listOf(item), "Carica la cartella «${item.name}»")
        }
    }

    private fun queueUpload(items: List<PhoneUploads.Item>, label: String) {
        val s = _screen.value as? Screen.Browser ?: return
        if (refusedAtNasRoot()) return
        val destination = if (s.path.isEmpty()) NasShares.shownName(s.share) else RemotePath.name(s.path)
        val resolver = getApplication<Application>().contentResolver
        askIfTaken(items.map { it.name }) { conflict ->
            val log = TransferLog()
            app.transfers.enqueue("$label in $destination") { fs, progress ->
                PhoneUploads.send(resolver, fs, items, s.share, s.path, conflict, log, progress)
            }
        }
    }

    /**
     * downloadTo() - saves a file of the NAS into a document the user created with the system picker
     * @param entry the file
     * @param uri the document
     *
     * The copy is checked against the NAS like every download.
     *
     * Context: launches a coroutine; the outcome arrives as a message.
     * Return: the job of the download.
     */
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

    // --- optional functions ---------------------------------------------------------------------------------------

    private val _extras = MutableStateFlow(readExtras())
    val extras: StateFlow<ExtrasState> = _extras.asStateFlow()

    private fun readExtras() = app.extras.let {
        ExtrasState(it.lock, it.wakeOnLan, it.wakeMac, it.followNas, it.autoplayVideo, it.showNasOnFolders)
    }

    /**
     * setLock() - turns the unlock with fingerprint, face or screen lock on or off
     * @param on the new value of the setting
     *
     * It is refused when the phone has no such lock set.
     *
     * Return: nothing.
     */
    fun setLock(on: Boolean) {
        if (on && !AppLock.available(app)) {
            message("Imposta prima un'impronta, il volto o un blocco schermo nelle impostazioni del telefono")
            return
        }
        app.extras.lock = on
        if (on) app.extras.lastUnlocked = System.currentTimeMillis()
        _extras.value = readExtras()
    }

    /**
     * setWakeOnLan() - turns the Wake-on-LAN button on or off
     * @param on the new value of the setting
     *
     * Return: nothing.
     */
    fun setWakeOnLan(on: Boolean) {
        app.extras.wakeOnLan = on
        _extras.value = readExtras()
    }

    /**
     * setWakeMac() - saves the MAC address the Wake-on-LAN packet goes to
     * @param mac the address as typed
     *
     * Return: nothing.
     */
    fun setWakeMac(mac: String) {
        app.extras.wakeMac = mac
        _extras.value = readExtras()
    }

    /**
     * setFollowNas() - turns on or off the address followed when the NAS changes it
     * @param on the new value of the setting
     *
     * Return: nothing.
     */
    fun setFollowNas(on: Boolean) {
        app.extras.followNas = on
        _extras.value = readExtras()
    }

    /**
     * setAutoplayVideo() - turns "videos start by themselves" on or off
     * @param on the new value of the setting
     *
     * Return: nothing.
     */
    fun setAutoplayVideo(on: Boolean) {
        app.extras.autoplayVideo = on
        _extras.value = readExtras()
    }

    /**
     * setShowNasOnFolders() - turns on or off the NAS and user shown under each folder of File
     * @param on the new value of the setting
     *
     * Return: nothing.
     */
    fun setShowNasOnFolders(on: Boolean) {
        app.extras.showNasOnFolders = on
        _extras.value = readExtras()
    }

    /**
     * wakeNas() - sends the Wake-on-LAN packet to the NAS
     *
     * Context: launches a coroutine; the outcome arrives as a message.
     * Return: the job.
     */
    fun wakeNas() = viewModelScope.launch {
        try {
            WakeOnLan.wake(app.extras.wakeMac, repo.host())
            message("Segnale di accensione inviato: il NAS di solito risponde dopo un paio di minuti")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            message(e.readable())
        }
    }

    private val _found = MutableStateFlow<List<FoundNas>?>(null)
    /** NAS announced on the Wi-Fi, for the address field of the settings; null until searched. */
    val found: StateFlow<List<FoundNas>?> = _found.asStateFlow()
    private val _finding = MutableStateFlow(false)
    val finding: StateFlow<Boolean> = _finding.asStateFlow()

    /**
     * findNas() - looks for NAS announced on the Wi-Fi (Bonjour)
     *
     * Context: launches a coroutine; the results arrive in [found].
     * Return: the job of the search.
     */
    fun findNas() = viewModelScope.launch {
        _finding.value = true
        try {
            _found.value = NasFinder.discover(app)
        } finally {
            _finding.value = false
        }
    }

    private var lastFollow = 0L

    /**
     * The NAS does not answer at its address: with the option on, looks for it on the network and moves to its new
     * address when the same NAS (same identity) answers there. At most once a minute.
     */
    private fun followIfMoved(error: Throwable) {
        if (!app.extras.followNas || !error.isConnectionLost() && error !is java.net.UnknownHostException) return
        val now = System.currentTimeMillis()
        if (now - lastFollow < 60_000) return
        lastFollow = now
        viewModelScope.launch {
            val candidates = runCatching { NasFinder.discover(app) }.getOrDefault(emptyList())
            for (nas in candidates) {
                if (repo.follow(nas.address)) {
                    message("Il NAS ha cambiato indirizzo: ora è ${nas.address}, aggiornato nelle impostazioni")
                    listingCache.clear()
                    refresh()
                    return@launch
                }
            }
        }
    }

    // --- music --------------------------------------------------------------------------------------------------

    val music: StateFlow<MusicState> = app.music.state

    /**
     * playMusic() - plays a song and, after it, the other songs of the folder as the list shows them
     * @param entry the song
     *
     * Return: nothing.
     */
    fun playMusic(entry: RemoteEntry) {
        val songs = _browser.value.visible.filter { it.isAudio }.ifEmpty { listOf(entry) }
        app.music.play(songs, songs.indexOf(entry).coerceAtLeast(0))
    }

    /**
     * musicToggle() - pauses the song playing, or plays it again
     *
     * Return: nothing.
     */
    fun musicToggle() = app.music.toggle()
    /**
     * musicNext() - skips to the next song
     *
     * Return: nothing.
     */
    fun musicNext() = app.music.next()
    /**
     * musicPrevious() - goes back to the previous song, or to the start of this one
     *
     * Return: nothing.
     */
    fun musicPrevious() = app.music.previous()
    /**
     * musicSeek() - moves within the song playing
     * @param position the time to go to, in milliseconds
     *
     * Return: nothing.
     */
    fun musicSeek(position: Long) = app.music.seek(position)
    /**
     * musicStop() - stops the music and closes the player
     *
     * Return: nothing.
     */
    fun musicStop() = app.music.stop()

    // --- ZIP archives ---------------------------------------------------------------------------------------------

    private val _archive = MutableStateFlow(ArchiveState())
    val archive: StateFlow<ArchiveState> = _archive.asStateFlow()

    private var openArchive: Pair<ZipArchive, RandomReader>? = null

    /**
     * openArchive() - opens a ZIP of the NAS like a folder
     * @param entry the archive
     *
     * Only its index is read, not the whole file.
     *
     * Return: nothing.
     */
    fun openArchive(entry: RemoteEntry) {
        val s = _screen.value as? Screen.Browser ?: return
        closeArchive()
        _screen.value = Screen.Archive(s, entry, "")
        _archive.value = ArchiveState(loading = true)
        viewModelScope.launch {
            try {
                openArchive = repo.io { fs ->
                    val reader = fs.openRandom(entry.share, entry.path)
                    try {
                        ZipArchive.read(reader) to reader
                    } catch (e: Exception) {
                        reader.close()
                        throw e
                    }
                }
                showArchiveDir()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _archive.value = ArchiveState(error = e.readable())
            }
        }
    }

    /**
     * openArchiveFolder() - opens a folder inside the archive being browsed
     * @param item the folder of the archive
     *
     * Return: nothing.
     */
    fun openArchiveFolder(item: ZipArchive.Entry) {
        val s = _screen.value as? Screen.Archive ?: return
        _screen.value = s.copy(dir = item.path)
        showArchiveDir()
    }

    private fun showArchiveDir() {
        val s = _screen.value as? Screen.Archive ?: return
        val (zip, _) = openArchive ?: return
        val items = zip.list(s.dir).sortedWith(compareByDescending<ZipArchive.Entry> { it.isDirectory }.thenBy { it.name.lowercase() })
        _archive.value = ArchiveState(items = items, loading = false)
    }

    private fun closeArchive() {
        openArchive?.second?.let { r -> viewModelScope.launch(Dispatchers.IO) { runCatching { r.close() } } }
        openArchive = null
    }

    /**
     * openFromArchive() - extracts one file of the archive to the phone and opens it with another app
     * @param item the file of the archive
     *
     * Nothing is written to the NAS.
     *
     * Context: launches a coroutine.
     * Return: the job.
     */
    fun openFromArchive(item: ZipArchive.Entry) = viewModelScope.launch {
        val (zip, _) = openArchive ?: return@launch
        val dir = app.shareCache.newFolder()
        val local = File(dir, item.name)
        try {
            withContext(Dispatchers.IO) {
                zip.open(item).use { input ->
                    local.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            _transfer.value = Transfer("Estraggo ${item.name}", done, item.size)
                        }
                    }
                }
            }
            app.shareCache.scheduleCleanup()
            events.send(UiEvent.OpenFile(local, item.name, edit = false))
        } catch (e: Exception) {
            dir.deleteRecursively()
            if (e is CancellationException) throw e
            message(e.readable())
        } finally {
            _transfer.value = null
        }
    }

    /**
     * extractFromArchive() - extracts part of the archive, or all of it, next to the archive on the NAS
     * @param item a file or folder of the archive, or null for everything
     *
     * Everything goes into a new folder named after the archive. It runs in the transfer queue.
     *
     * Return: nothing.
     */
    fun extractFromArchive(item: ZipArchive.Entry?) {
        val s = _screen.value as? Screen.Archive ?: return
        val zipEntry = s.entry
        val folder = s.returnTo
        val base = if (item == null) "" else RemotePath.parent(item.path)
        val targetName = item?.name ?: zipEntry.name.substringBeforeLast('.').ifEmpty { zipEntry.name }
        val start = { conflict: Conflict ->
            val log = TransferLog()
            app.transfers.enqueue("Estrai «$targetName» da ${zipEntry.name}") { fs, progress ->
                fs.openRandom(zipEntry.share, zipEntry.path).use { reader ->
                    val zip = ZipArchive.read(reader)
                    val files = zip.filesUnder(item?.path ?: "")
                    val toDir = if (item == null) RemotePath.child(folder.path, targetName) else folder.path
                    if (item == null && fs.stat(folder.share, toDir) == null) fs.createDirectory(folder.share, toDir)
                    ZipExtract.extract(fs, zip, files, base, folder.share, toDir, conflict, log, progress)
                }
                when (log.skipped) {
                    0 -> "estratto in ${RemotePath.name(folder.path).ifEmpty { folder.share }}"
                    else -> "${log.skipped} file già presenti, lasciati com'erano"
                }
            }
            message("Estrazione in coda: la trovi in ${RemotePath.name(folder.path).ifEmpty { folder.share }}")
        }
        // names in the NAS folder as it was listed before opening the archive
        val taken = (listingCache["${folder.share}/${folder.path}"] ?: emptyList()).mapTo(HashSet()) { it.name.lowercase() }
        if (targetName.lowercase() in taken) _conflictAsk.value = ConflictAsk(listOf(targetName), 1) { start(it) } else start(Conflict.KeepBoth)
    }

    // --- bin of the file browser ------------------------------------------------------------------------------------

    private val _trash = MutableStateFlow(TrashState())
    val trash: StateFlow<TrashState> = _trash.asStateFlow()

    /**
     * openTrash() - opens the bin of the File section and reads it
     *
     * Return: nothing.
     */
    fun openTrash() {
        val here = _screen.value
        _screen.value = Screen.Trash(if (here is Screen.Trash) here.returnTo else here)
        loadTrash()
    }

    /**
     * loadTrash() - reads the bin of every share with a saved folder
     *
     * What is older than 30 days is deleted for good first.
     *
     * Context: launches a coroutine; the content arrives in [trash].
     * Return: nothing.
     */
    fun loadTrash() {
        _trash.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val browsing = ((_screen.value as? Screen.Trash)?.returnTo as? Screen.Browser)?.share
                val saved = app.folders.all().map { it.share } + listOfNotNull(browsing)
                val items = repo.io { fs ->
                    // the folder of a whole other NAS stands for all its shares: their bins are shown too
                    val shares = saved.flatMap { share ->
                        if (!NasShares.isRoot(share)) listOf(share)
                        else runCatching { fs.list(share, "").map { it.share } }.getOrDefault(emptyList())
                    }.distinctBy { it.lowercase() }
                    shares.flatMap { share ->
                        runCatching { FileTrash.purge(fs, share) }
                        runCatching { FileTrash.contents(fs, share) }.getOrDefault(emptyList())
                    }
                }.sortedByDescending { it.deletedAt }
                _trash.value = TrashState(items = items)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _trash.update { it.copy(loading = false, error = e.readable()) }
            }
        }
    }

    /**
     * restoreFromTrash() - puts elements of the bin back where they were
     * @param items the elements
     *
     * Context: launches a coroutine.
     * Return: the job.
     */
    fun restoreFromTrash(items: List<FileTrash.Item>) = viewModelScope.launch {
        try {
            val places = repo.io { fs -> items.map { FileTrash.restore(fs, it) } }
            val where = RemotePath.parent(places[0]).ifEmpty { items[0].share }
            message(if (items.size == 1) "«${items[0].entry.name}» ripristinato in $where" else "${items.size} elementi ripristinati")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            message(e.readable())
        }
        listingCache.clear()
        app.nasChanged.tryEmit(null)
        if (_screen.value is Screen.Trash) loadTrash() else refresh()
    }

    /**
     * deleteFromTrash() - deletes elements of the bin for good
     * @param items the elements
     *
     * Context: launches a coroutine; the progress shows in [deletion].
     * Return: the job.
     */
    fun deleteFromTrash(items: List<FileTrash.Item>) = viewModelScope.launch {
        _trash.update { it.copy(working = true) }
        try {
            val skipped = repo.io { fs -> items.count { !FileTrash.delete(fs, it) } }
            message(
                when {
                    items.size == 1 && skipped == 0 -> "Eliminato definitivamente"
                    skipped == 0 -> "${items.size} elementi eliminati definitivamente"
                    else -> "${items.size - skipped} eliminati, $skipped non erano più nel cestino"
                }
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            message(e.readable())
        }
        loadTrash()
    }

    // --- open and edit ----------------------------------------------------------------------------------------------

    /**
     * open() - downloads a file into a private folder and asks the UI to open it in another app
     * @param entry the file
     * @param edit true when the other app may change it
     *
     * The copy is tracked: when the user comes back and it changed, [syncEdits] puts it back on the NAS.
     *
     * Context: launches a coroutine.
     * Return: the job of the download.
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

    // --- share ------------------------------------------------------------------------------------------------------

    /**
     * share() - hands files of the NAS to the share sheet of Android
     * @param entries the files
     *
     * They are downloaded into a private, temporary folder (checked against the NAS like every download). Nothing is
     * written to the phone's public folders, so the backup never sees them; the copies are deleted once the user is back
     * in the app, or a few minutes later anyway.
     *
     * Context: launches a coroutine.
     * Return: the job of the downloads.
     */
    fun share(entries: List<RemoteEntry>) = viewModelScope.launch {
        val files = entries.filter { !it.isDirectory }
        if (files.isEmpty()) return@launch
        if (files.size < entries.size) message("Le cartelle non si possono condividere: condivido solo i file")
        val dir = app.shareCache.newFolder()
        try {
            val copies = repo.io { fs ->
                val used = HashSet<String>()
                files.mapIndexed { i, entry ->
                    // two files with the same name from different folders: the second becomes "name (2).ext"
                    val local = File(dir, uniqueName(entry.name, used))
                    val label = if (files.size == 1) "Preparo ${entry.name}" else "Preparo ${i + 1} di ${files.size}: ${entry.name}"
                    val sent = local.outputStream().use { out ->
                        Integrity.download(fs, entry.share, entry.path, out) { done, total ->
                            _transfer.value = Transfer(label, done, total)
                        }
                    }
                    if (EditSync.sha256(local) != sent) throw Integrity.mismatch(entry.name, "la copia sul telefono è diversa")
                    local
                }
            }
            app.shareCache.scheduleCleanup()
            events.send(UiEvent.Share(copies))
        } catch (e: Exception) {
            dir.deleteRecursively()
            if (e is CancellationException) throw e
            message(e.readable())
        } finally {
            _transfer.value = null
        }
    }

    /**
     * cleanShared() - deletes the copies made for a share, once the user is back in the app
     *
     * The receiving app has read them by then; copies younger than a short grace time stay.
     *
     * Return: nothing.
     */
    fun cleanShared() {
        viewModelScope.launch(Dispatchers.IO) { app.shareCache.clean(ShareCache.GRACE_MS) }
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name.lowercase())) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        var n = 2
        while (true) {
            val candidate = name.substring(0, dot) + " ($n)" + name.substring(dot)
            if (used.add(candidate.lowercase())) return candidate
            n++
        }
    }

    /**
     * openFailed() - drops the copy of a file no app could open
     * @param file the copy
     *
     * Return: nothing.
     */
    fun openFailed(file: File) {
        sessions.all().firstOrNull { it.localFile == file }?.let { sessions.remove(it) }
        message("Nessuna app installata può aprire questo file")
    }

    /**
     * syncEdits() - puts back on the NAS the files edited in other apps
     *
     * Called whenever the app comes back to the foreground, typically after the editor closed. A file that also changed
     * on the NAS meanwhile is asked about in [syncPrompt].
     *
     * Return: nothing.
     */
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

    /**
     * resolveOverwrite() - conflict answer: replaces the NAS version with the phone's
     *
     * Return: nothing.
     */
    fun resolveOverwrite() = resolve { session ->
        uploadEdit(session, session.path, null)
        message("«${RemotePath.name(session.path)}» sovrascritto sul NAS")
    }

    /**
     * resolveKeepBoth() - conflict answer: keeps both, the phone's version goes next to the original as a copy
     *
     * Return: nothing.
     */
    fun resolveKeepBoth() = resolve { session ->
        val copy = repo.io { EditSync.freeCopyPath(it, session.share, session.path, "dal telefono") }
        uploadEdit(session, copy, null)
        message("Salvato come «${RemotePath.name(copy)}»")
    }

    /**
     * resolveDiscard() - conflict answer: throws the phone's changes away
     *
     * Return: nothing.
     */
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

    /*
     * The folder of another NAS only lists its shares: nothing can be created, pasted or deleted there. True, after
     * telling the user, when the folder being browsed is one of those.
     */
    private fun refusedAtNasRoot(): Boolean {
        val s = _screen.value as? Screen.Browser ?: return false
        if (!NasShares.isRoot(s.share)) return false
        message(MultiNasFileSystem.ROOT_READ_ONLY)
        return true
    }

    private fun folderOperation(done: String, block: suspend (share: String, dir: String) -> Unit) {
        val s = _screen.value as? Screen.Browser ?: return
        if (refusedAtNasRoot()) return
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

    /**
     * showMessage() - shows a short message at the bottom of the screen, for screens with a view model of their own
     * @param text what to say; nothing is shown when empty
     *
     * Return: nothing.
     */
    fun showMessage(text: String) {
        if (text.isNotEmpty()) message(text)
    }

    private companion object {
        /** Unchanged copies are forgotten after a week: the editor is long closed by then. */
        const val KEEP_UNCHANGED_MS = 7L * 24 * 60 * 60 * 1000
        /** Folders whose scroll position is remembered; past this the memory starts again, it only saves a scroll. */
        const val SCROLL_MEMORY_MAX = 200
    }
}
