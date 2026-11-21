/*
 * CleanupViewModel.kt - state and actions of the quick cleanup
 *
 * Picks the photos to look at, keeps the choices on the NAS, moves the discarded ones to the bin and empties it. The
 * rules are CleanupRules' and PhotoKinds'.
 *
 * Version: 1.28.3
 */
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
import io.github.thamichele.smbcommander.core.PhotoKind
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.unlessGone
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.ui.Deletion
import io.github.thamichele.smbcommander.ui.DeletionProgress
import io.github.thamichele.smbcommander.ui.DeletionStep
import io.github.thamichele.smbcommander.ui.readable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One photo or video to decide on, and why it was picked (null in "all the others"). */
data class CleanupCard(val entry: RemoteEntry, val reason: CleanupReason?)

enum class CleanupTab { Suspects, Others, Duplicates }

/** Bigger files (long videos) are not read twice over the network just to compare them. */
const val MAX_COMPARE_BYTES = 150L * 1024 * 1024

/** Two copies of the same photo side by side: [first] is the likeliest original. */
data class DuplicatePair(val first: RemoteEntry, val second: RemoteEntry) {
    val key: String get() = first.path + "\n" + second.path
}

data class CleanupState(
    val folder: SavedFolder? = null,
    val loading: Boolean = false,
    /** Picked by the rules: screenshots, chat images, copies, bursts... */
    val suspects: List<CleanupCard> = emptyList(),
    /** Everything else not yet looked at, newest first. */
    val others: List<CleanupCard> = emptyList(),
    /** Copies of the same photo, likeliest original first; one comparison at a time, two by two. */
    val duplicateGroups: List<List<RemoteEntry>> = emptyList(),
    val tab: CleanupTab = CleanupTab.Suspects,
    /** The group of "all the others" being looked at; null for all of them. */
    val othersKind: PhotoKind? = null,
    /** What each photo of "all the others" shows (by path), as soon as it has been looked at. */
    val kinds: Map<String, PhotoKind> = emptyMap(),
    /** Photos of "all the others" still to be looked at, while it goes on. */
    val sorting: Int = 0,
    /** Cards decided in this session, so the queue moves on. */
    val decided: Set<String> = emptySet(),
    val canUndo: Boolean = false,
    val trashCount: Int = 0,
    val trashBytes: Long = 0,
    val keptCount: Int = 0,
    val discardedCount: Int = 0,
    val error: String? = null,
) {
    /**
     * Photos still waiting in the Duplicates tab: those of a group with at least two copies not decided yet. They
     * stay
     * out of the swipe queues until they are on their own, so the same photo is not asked about in two places.
     */
    val inDuplicates: Set<String> by lazy {
        buildSet {
            for (group in duplicateGroups) {
                val left = group.filter { it.path !in decided }
                if (left.size >= 2) left.forEach { add(it.path) }
            }
        }
    }

    private fun open(card: CleanupCard) = card.entry.path !in decided && card.entry.path !in inDuplicates

    /** Worked out once per state: with thousands of photos it must not run at every frame. */
    val queue: List<CleanupCard> by lazy {
        when (tab) {
            CleanupTab.Suspects -> suspects
            CleanupTab.Others -> if (othersKind == null) others else others.filter { kinds[it.entry.path] == othersKind }
            CleanupTab.Duplicates -> emptyList()
        }.filter(::open)
    }

    /** How many photos still open in each group of "all the others". */
    val othersByKind: Map<PhotoKind, Int> by lazy {
        others.filter(::open).mapNotNull { kinds[it.entry.path] }.groupingBy { it }.eachCount()
    }

    /** The comparisons still to make: in each group, the first two copies not decided yet. */
    val pairs: List<DuplicatePair> by lazy {
        duplicateGroups.mapNotNull { group ->
            val left = group.filter { it.path !in decided }
            if (left.size >= 2) DuplicatePair(left[0], left[1]) else null
        }
    }
    val suspectsLeft: Int by lazy { suspects.count(::open) }
    val othersLeft: Int by lazy { others.count(::open) }
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

    // replaced by the loading coroutine, read by the swipes on the main thread
    @Volatile
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

    private companion object {
        /** Photos sorted together: the thumbnails missing are fetched side by side. */
        const val SORT_BATCH = 4
    }

    /** NAS work runs one step at a time, in the order of the swipes. */
    private val nas = Mutex()
    private var flushJob: Job? = null
    private var loadJob: Job? = null
    private var sortJob: Job? = null

    /**
     * start() - builds the queues from what the gallery shows
     * @param media every photo and video of the gallery
     *
     * The lists of kept photos come from the NAS. Nothing happens when the same folder is already loaded.
     *
     * Return: nothing; the queues arrive in [state].
     */
    fun start(media: List<RemoteEntry>) {
        val folder = app.backupSettings.load().destination ?: return
        if (_state.value.folder == folder && (_state.value.loading || _state.value.suspects.isNotEmpty() || _state.value.others.isNotEmpty())) return
        loadJob?.cancel()
        sortJob?.cancel()
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
                val known = others.mapNotNull { card -> app.photoSorter.known(card.entry)?.let { card.entry.path to it } }.toMap()
                // copies among all the photos, kept ones too: a kept photo can still have a copy. Of those already
                // kept, only the first stays, as the one the others are compared with
                val duplicates = CleanupRules.duplicates(media).mapNotNull { group ->
                    val open = group.filterNot { kept.containsPath(it, folder.path) }
                    if (open.isEmpty()) return@mapNotNull null
                    val anchor = group.firstOrNull { kept.containsPath(it, folder.path) }
                    (listOfNotNull(anchor) + open).takeIf { it.size >= 2 }
                }
                _state.update {
                    it.copy(
                        loading = false, suspects = suspects, others = others, duplicateGroups = duplicates,
                        keptCount = kept.lines.size, kinds = known, sorting = others.size - known.size,
                    )
                }
                sortJob = sortOthers(others.map { it.entry }.filter { it.path !in known })
                refreshTrash()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.readable()) }
            }
        }
    }

    /**
     * reload() - starts over with what the gallery shows now, keeping the lists on the NAS
     * @param media every photo and video of the gallery
     *
     * Return: nothing.
     */
    fun reload(media: List<RemoteEntry>) {
        _state.update { it.copy(folder = null) }
        start(media)
    }

    /**
     * showTab() - shows one of the tabs
     * @param tab the tab
     *
     * Return: nothing.
     */
    fun showTab(tab: CleanupTab) = _state.update { it.copy(tab = tab) }

    /**
     * showKind() - shows one group of "all the others"
     * @param kind the group, or null for all of them
     *
     * Return: nothing.
     */
    fun showKind(kind: PhotoKind?) = _state.update { it.copy(tab = CleanupTab.Others, othersKind = kind) }

    /**
     * Looks at the photos of "all the others" not sorted yet, newest first, a few at a time, in the background lane
     * of
     * the thumbnails: the groups fill up while the user swipes. A photo that cannot be read stays only in "all".
     */
    private fun sortOthers(photos: List<RemoteEntry>): Job = viewModelScope.launch(Dispatchers.IO) {
        var left = photos.size
        val found = HashMap<String, PhotoKind>()
        var shown = System.currentTimeMillis()
        for (batch in photos.chunked(SORT_BATCH)) {
            // heavy work on the phone: it waits while the app is in the background
            app.inFront.first { it }
            batch.map { photo ->
                async { runCatching { photo.path to app.photoSorter.sort(photo) }.getOrNull() }
            }.awaitAll().filterNotNull().toMap(found)
            left -= batch.size
            // the counts move about once a second, not at every photo: thousands of them would mean thousands of
            // recompositions
            if (left == 0 || System.currentTimeMillis() - shown > 1_000) {
                val add = found.toMap()
                found.clear()
                shown = System.currentTimeMillis()
                _state.update { it.copy(kinds = it.kinds + add, sorting = left) }
            }
        }
    }

    /**
     * keepOnly() - in a pair of copies, keeps one where it is and sends the other to the bin
     * @param pair the two copies
     * @param keep the one that stays
     *
     * Return: nothing.
     */
    fun keepOnly(pair: DuplicatePair, keep: RemoteEntry) {
        val root = _state.value.folder?.path ?: return
        val other = if (keep == pair.first) pair.second else pair.first
        discard(CleanupCard(other, CleanupReason(CleanupReason.Kind.Duplicate, "tenuta ${CleanupRules.relative(keep.path, root)}")))
    }

    /**
     * keepBoth() - keeps both copies: the first one is then compared with the next copy
     * @param pair the two copies
     *
     * Return: nothing.
     */
    fun keepBoth(pair: DuplicatePair) = keep(CleanupCard(pair.second, null))

    private val _sameContent = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    /** For pairs of the same size, whether the two files are the same bytes (SHA-256), once worked out. */
    val sameContent: StateFlow<Map<String, Boolean>> = _sameContent.asStateFlow()

    /**
     * compare() - compares the two files of a pair byte for byte
     * @param pair the two copies
     *
     * Only when they have the same size and are not too big to read twice over the network.
     *
     * Return: nothing; the outcome arrives in [sameContent].
     */
    fun compare(pair: DuplicatePair) {
        if (pair.first.size != pair.second.size || pair.first.size > MAX_COMPARE_BYTES || pair.key in _sameContent.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val same = runCatching {
                app.repository.io { fs ->
                    Integrity.remoteSha256(fs, pair.first.share, pair.first.path) == Integrity.remoteSha256(fs, pair.second.share, pair.second.path)
                }
            }.getOrNull() ?: return@launch
            _sameContent.update { it + (pair.key to same) }
        }
    }

    /**
     * keep() - keeps a photo: it leaves the queue and goes in the list on the NAS
     * @param card the photo
     *
     * Return: nothing.
     */
    fun keep(card: CleanupCard) {
        val folder = _state.value.folder ?: return
        kept.add(card.entry, folder.path)
        keptRemoved -= KeptPhotos.lineOf(card.entry, folder.path)
        synchronized(history) { history.addLast(Action.Keep(card)) }
        decided(card, kept = 1)
        scheduleFlush()
    }

    /**
     * discard() - sends a photo to the bin of the backup folder
     * @param card the photo
     *
     * Return: nothing; a failure comes back as an error and the card returns.
     */
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
                        val inTrash = fs.unlessGone(card.entry.share, card.entry.path) { CleanupTrash.discard(fs, card.entry, folder.path) }
                        if (inTrash == null) {
                            // already gone from the NAS (deleted or moved from another device): nothing to do, and
                            // nothing goes on the deleted list
                            app.deletedPhotos.emit(listOf(card.entry))
                            return@withLock
                        }
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
                        _state.update { it.copy(error = "«${card.entry.name}» non spostata nel cestino: ${e.readable()}") }
                    }
                }
            } finally {
                action.done.complete()
            }
            refreshTrash()
        }
        scheduleFlush()
    }

    /**
     * undo() - takes back the last choice: the card comes back, and a discarded photo returns to its folder
     *
     * Return: nothing.
     */
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
                        _state.update { it.copy(error = "Non riesco a rimettere «${action.card.entry.name}» al suo posto: ${e.readable()}") }
                    }
                }
                scheduleFlush()
                refreshTrash()
            }
        }
    }

    /**
     * clearError() - hides the error shown
     *
     * Return: nothing.
     */
    fun clearError() = _state.update { it.copy(error = null) }

    private val deletions = DeletionProgress()

    /** The bin being emptied, file by file, or just emptied: shown in a window with the outcome of each file. */
    val deletion: StateFlow<Deletion?> = deletions.state

    /**
     * stopDeletion() - stops emptying the bin after the file being deleted now
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

    /**
     * emptyTrash() - deletes the bin for good, one file at a time, each step shown by [deletion]
     *
     * A file no longer there is skipped, one that fails stays in the bin and does not stop the others.
     *
     * Return: nothing.
     */
    fun emptyTrash() {
        val folder = _state.value.folder ?: return
        deletions.start()
        var d = Deletion(total = 0, preparing = 0, preparingNote = "Leggo il contenuto del cestino")
        deletions.show(d)
        viewModelScope.launch(Dispatchers.IO) {
            nas.withLock {
                fun publish(next: Deletion) {
                    d = next
                    deletions.show(next)
                }
                try {
                    val fs = app.repository.fs()
                    val files = CleanupTrash.contents(fs, folder.share, folder.path)
                    publish(Deletion(total = files.size))
                    for (file in files) {
                        if (deletions.isStopRequested) break
                        publish(d.copy(current = file))
                        val step = DeletionProgress.step(file) { CleanupTrash.delete(fs, folder.share, file.path) }
                        publish(d.copy(steps = d.steps + step))
                    }
                    CleanupTrash.removeFolders(fs, folder.share, folder.path)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    publish(d.copy(error = e.readable()))
                } finally {
                    publish(d.copy(current = null, finished = true))
                }
                val gone = d.steps.filter { it.outcome != DeletionStep.Outcome.Failed }.map { it.entry.path }
                forgetDeleted(d.skipped.map { it.path })
                // what left the bin can no longer be put back
                val goneSet = gone.toSet()
                val more = synchronized(history) {
                    history.removeAll { it is Action.Discard && it.inTrash in goneSet }
                    history.isNotEmpty()
                }
                _state.update { it.copy(canUndo = more) }
            }
            scheduleFlush()
            refreshTrash()
        }
    }

    /**
     * restoreTrash() - puts everything in the bin back where it was, and out of the deleted list
     *
     * Return: nothing.
     */
    fun restoreTrash() {
        val folder = _state.value.folder ?: return
        viewModelScope.launch(Dispatchers.IO) {
            nas.withLock {
                try {
                    val fs = app.repository.fs()
                    val entries = ArrayList<String>()
                    val skipped = ArrayList<String>()
                    for (file in CleanupTrash.contents(fs, folder.share, folder.path)) {
                        val restored = fs.unlessGone(folder.share, file.path) {
                            val entry = DeletedPhotos.entry(file.size, Integrity.remoteSha256(fs, folder.share, file.path))
                            CleanupTrash.restore(fs, folder.share, file.path, CleanupTrash.originalOf(file.path, folder.path))
                            entry
                        }
                        if (restored != null) entries += restored else skipped += file.path
                    }
                    forgetDeleted(skipped)
                    synchronized(this@CleanupViewModel) {
                        deletedToAdd.removeAll(entries.toSet())
                        deletedToRemove += entries
                    }
                    val more = synchronized(history) { history.removeAll { it is Action.Discard }; history.isNotEmpty() }
                    _state.update { it.copy(canUndo = more, decided = emptySet()) }
                    app.nasChanged.emit(NasChange(folder.share, folder.path))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    _state.update { it.copy(error = "Ripristino non completato: ${e.readable()}") }
                }
            }
            flush()
            refreshTrash()
        }
    }

    /**
     * Files of the bin that were gone when it was emptied or restored (deleted or moved from another device) come off
     * the deleted list, so a photo moved elsewhere is not remembered as deleted. Their line is known for the photos
     * discarded since this screen was opened.
     */
    private fun forgetDeleted(gone: List<String>) {
        if (gone.isEmpty()) return
        val paths = gone.toSet()
        val lines = synchronized(history) {
            history.mapNotNull { action -> (action as? Action.Discard)?.takeIf { it.inTrash in paths }?.deletedEntry }
        }
        synchronized(this) {
            deletedToAdd.removeAll(lines.toSet())
            deletedToRemove += lines
        }
    }

    /**
     * flushNow() - writes the pending choices to the NAS now, e.g. when leaving the screen
     *
     * Return: nothing.
     */
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
                _state.update { it.copy(error = "Scelte non ancora salvate sul NAS: ${e.readable()}") }
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
