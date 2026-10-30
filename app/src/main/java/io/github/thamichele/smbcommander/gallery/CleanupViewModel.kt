package io.github.thamichele.smbcommander.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.thamichele.smbcommander.NasChange
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.CleanupReason
import io.github.thamichele.smbcommander.core.CleanupRules
import io.github.thamichele.smbcommander.core.CleanupTrash
import io.github.thamichele.smbcommander.core.DeletedPhotos
import io.github.thamichele.smbcommander.core.GalleryDates
import io.github.thamichele.smbcommander.core.Integrity
import io.github.thamichele.smbcommander.core.KeptPhotos
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.SavedFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One photo or video to decide on, and why it was picked (null in "all the others"). */
data class CleanupCard(val entry: RemoteEntry, val reason: CleanupReason?)

data class CleanupState(
    val folder: SavedFolder? = null,
    val loading: Boolean = false,
    /** Picked by the rules: screenshots, chat images, copies, bursts... */
    val suspects: List<CleanupCard> = emptyList(),
    /** Everything else not yet looked at, newest first. */
    val others: List<CleanupCard> = emptyList(),
    val showingOthers: Boolean = false,
    /** Cards decided in this session, so the queue moves on. */
    val decided: Set<String> = emptySet(),
    val canUndo: Boolean = false,
    val trashCount: Int = 0,
    val trashBytes: Long = 0,
    val keptCount: Int = 0,
    val discardedCount: Int = 0,
    val error: String? = null,
) {
    /** Worked out once per state: with thousands of photos it must not run at every frame. */
    val queue: List<CleanupCard> by lazy { (if (showingOthers) others else suspects).filterNot { it.entry.path in decided } }
    val suspectsLeft: Int by lazy { suspects.count { it.entry.path !in decided } }
    val othersLeft: Int by lazy { others.count { it.entry.path !in decided } }
}

/**
 * The quick cleanup: swipe left to discard, right to keep. Discarded photos go to the bin inside the backup folder
 * (a rename: instant, nothing copied) and to the list of deleted photos, so the backup never brings them back; kept
 * ones go to the list of kept photos, so they are not asked about again. Both lists live in the backup folder on the
 * NAS. Every choice can be undone, newest first.
 */
class CleanupViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmbCommanderApp
    private val _state = MutableStateFlow(CleanupState())
    val state: StateFlow<CleanupState> = _state.asStateFlow()

    private var kept = KeptPhotos()
    private val keptRemoved = HashSet<String>()
    private val deletedToAdd = LinkedHashSet<String>()
    private val deletedToRemove = HashSet<String>()

    private sealed interface Action {
        val card: CleanupCard

        data class Keep(override val card: CleanupCard) : Action

        /** [done] completes once the photo is in the bin, with where it went and its entry in the deleted list. */
        class Discard(override val card: CleanupCard) : Action {
            val done: CompletableJob = Job()
            @Volatile
            var inTrash: String? = null
            @Volatile
            var deletedEntry: String? = null
        }
    }

    private val history = ArrayDeque<Action>()

    /** NAS work runs one step at a time, in the order of the swipes. */
    private val nas = Mutex()
    private var flushJob: Job? = null
    private var loadJob: Job? = null

    /** Builds the queues from what the gallery shows; the lists of kept photos come from the NAS. */
    fun start(media: List<RemoteEntry>) {
        val folder = app.backupSettings.load().destination ?: return
        if (_state.value.folder == folder && (_state.value.loading || _state.value.suspects.isNotEmpty() || _state.value.others.isNotEmpty())) return
        loadJob?.cancel()
        _state.value = CleanupState(folder = folder, loading = true)
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                kept = app.repository.io { KeptPhotos.read(it, folder.share, folder.path) }
                val todo = media.filterNot { kept.contains(it, folder.path) }
                val reasons = CleanupRules.classify(todo, folder.path)
                val newestFirst = compareByDescending<RemoteEntry> { GalleryDates.of(it).sortKey }
                val suspects = reasons.entries
                    .sortedWith(compareBy<Map.Entry<RemoteEntry, CleanupReason>> { it.value.kind.ordinal }.thenComparator { a, b -> newestFirst.compare(a.key, b.key) })
                    .map { CleanupCard(it.key, it.value) }
                val others = todo.filterNot { it in reasons }.sortedWith(newestFirst).map { CleanupCard(it, null) }
                _state.update { it.copy(loading = false, suspects = suspects, others = others, keptCount = kept.lines.size) }
                refreshTrash()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** Starts over with what the gallery shows now, keeping the lists on the NAS. */
    fun reload(media: List<RemoteEntry>) {
        _state.update { it.copy(folder = null) }
        start(media)
    }

    fun showOthers(on: Boolean) = _state.update { it.copy(showingOthers = on) }

    fun keep(card: CleanupCard) {
        val folder = _state.value.folder ?: return
        kept.add(card.entry, folder.path)
        keptRemoved -= KeptPhotos.lineOf(card.entry, folder.path)
        synchronized(history) { history.addLast(Action.Keep(card)) }
        decided(card, kept = 1)
        scheduleFlush()
    }

    fun discard(card: CleanupCard) {
        val folder = _state.value.folder ?: return
        val action = Action.Discard(card)
        synchronized(history) { history.addLast(action) }
        decided(card, discarded = 1)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                nas.withLock {
                    try {
                        val fs = app.repository.fs()
                        val inTrash = CleanupTrash.discard(fs, card.entry, folder.path)
                        action.inTrash = inTrash
                        // hashed where it now is: the backup will recognise the photo and never upload it again
                        val entry = DeletedPhotos.entry(card.entry.size, Integrity.remoteSha256(fs, card.entry.share, inTrash))
                        action.deletedEntry = entry
                        synchronized(this@CleanupViewModel) {
                            deletedToRemove -= entry
                            deletedToAdd += entry
                        }
                        app.deletedPhotos.emit(listOf(card.entry))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _state.update { it.copy(error = "«${card.entry.name}» non spostata nel cestino: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }
            } finally {
                action.done.complete()
            }
            refreshTrash()
        }
        scheduleFlush()
    }

    /** Takes back the last choice: the card comes back, and a discarded photo returns to its folder. */
    fun undo() {
        val folder = _state.value.folder ?: return
        val (action, more) = synchronized(history) { (history.removeLastOrNull() ?: return) to history.isNotEmpty() }
        _state.update {
            it.copy(
                decided = it.decided - action.card.entry.path,
                canUndo = more,
                keptCount = it.keptCount - if (action is Action.Keep) 1 else 0,
                discardedCount = it.discardedCount - if (action is Action.Discard) 1 else 0,
            )
        }
        when (action) {
            is Action.Keep -> {
                kept.remove(action.card.entry, folder.path)
                keptRemoved += KeptPhotos.lineOf(action.card.entry, folder.path)
                scheduleFlush()
            }
            is Action.Discard -> viewModelScope.launch(Dispatchers.IO) {
                action.done.join()
                nas.withLock {
                    val inTrash = action.inTrash ?: return@withLock
                    try {
                        CleanupTrash.restore(app.repository.fs(), folder.share, inTrash, action.card.entry.path)
                        action.deletedEntry?.let { entry ->
                            synchronized(this@CleanupViewModel) {
                                if (!deletedToAdd.remove(entry)) deletedToRemove += entry
                            }
                        }
                        app.nasChanged.emit(NasChange(folder.share, folder.path))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        _state.update { it.copy(error = "Non riesco a rimettere «${action.card.entry.name}» al suo posto: ${e.message}") }
                    }
                }
                scheduleFlush()
                refreshTrash()
            }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    /** Deletes the bin for good. */
    fun emptyTrash() {
        val folder = _state.value.folder ?: return
        viewModelScope.launch(Dispatchers.IO) {
            nas.withLock {
                try {
                    CleanupTrash.empty(app.repository.fs(), folder.share, folder.path)
                    // what was in the bin can no longer be put back
                    val more = synchronized(history) { history.removeAll { it is Action.Discard }; history.isNotEmpty() }
                    _state.update { it.copy(canUndo = more) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = "Cestino non svuotato: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
            refreshTrash()
        }
    }

    /** Puts everything in the bin back where it was, and out of the deleted list. */
    fun restoreTrash() {
        val folder = _state.value.folder ?: return
        viewModelScope.launch(Dispatchers.IO) {
            nas.withLock {
                try {
                    val fs = app.repository.fs()
                    val entries = ArrayList<String>()
                    for (file in CleanupTrash.contents(fs, folder.share, folder.path)) {
                        entries += DeletedPhotos.entry(file.size, Integrity.remoteSha256(fs, folder.share, file.path))
                        CleanupTrash.restore(fs, folder.share, file.path, CleanupTrash.originalOf(file.path, folder.path))
                    }
                    synchronized(this@CleanupViewModel) {
                        deletedToAdd.removeAll(entries.toSet())
                        deletedToRemove += entries
                    }
                    val more = synchronized(history) { history.removeAll { it is Action.Discard }; history.isNotEmpty() }
                    _state.update { it.copy(canUndo = more, decided = emptySet()) }
                    app.nasChanged.emit(NasChange(folder.share, folder.path))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = "Ripristino non completato: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
            flush()
            refreshTrash()
        }
    }

    /** Writes the pending choices to the NAS now, e.g. when leaving the screen. */
    fun flushNow() {
        flushJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { flush() }
    }

    private fun decided(card: CleanupCard, kept: Int = 0, discarded: Int = 0) = _state.update {
        it.copy(
            decided = it.decided + card.entry.path,
            canUndo = true,
            keptCount = it.keptCount + kept,
            discardedCount = it.discardedCount + discarded,
        )
    }

    /** A few seconds after the last swipe, so a fast series of swipes costs one write. */
    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = viewModelScope.launch(Dispatchers.IO) {
            delay(3_000)
            flush()
        }
    }

    private suspend fun flush() = withContext(NonCancellable) {
        val folder = _state.value.folder ?: return@withContext
        nas.withLock {
            val (add, remove, removedKept) = synchronized(this@CleanupViewModel) {
                Triple(deletedToAdd.toList(), deletedToRemove.toList(), keptRemoved.toList()).also {
                    deletedToAdd.clear()
                    deletedToRemove.clear()
                    keptRemoved.clear()
                }
            }
            try {
                val fs = app.repository.fs()
                KeptPhotos.write(fs, folder.share, folder.path, kept, removedKept)
                if (add.isNotEmpty()) DeletedPhotos.add(fs, folder.share, folder.path, add)
                if (remove.isNotEmpty()) DeletedPhotos.remove(fs, folder.share, folder.path, remove)
            } catch (e: Exception) {
                // kept for the next try
                synchronized(this@CleanupViewModel) {
                    deletedToAdd += add
                    deletedToRemove += remove
                    keptRemoved += removedKept
                }
                _state.update { it.copy(error = "Scelte non ancora salvate sul NAS: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    private suspend fun refreshTrash() {
        val folder = _state.value.folder ?: return
        val contents = runCatching { app.repository.io { CleanupTrash.contents(it, folder.share, folder.path) } }.getOrNull() ?: return
        _state.update { it.copy(trashCount = contents.size, trashBytes = contents.sumOf { e -> e.size }) }
    }

    override fun onCleared() {
        // the app's scope outlives this screen: the last choices still reach the NAS
        CoroutineScope(Dispatchers.IO).launch { flush() }
    }
}
