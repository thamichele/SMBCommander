package io.github.thamichele.smbcommander.data

import io.github.thamichele.smbcommander.core.NasFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class TransferJob(
    val id: Long,
    /** "Copia «Foto» in Archivio" */
    val label: String,
    val state: State = State.Waiting,
    /** The file being transferred right now, for folders with many files. */
    val current: String = "",
    val done: Long = 0,
    val total: Long = -1,
    val error: String? = null,
    /** Said when the job ends well, such as "3 già presenti, saltati". */
    val note: String? = null,
    /** While [State.Retrying]: seconds before the next attempt. */
    val retryIn: Int = 0,
) {
    enum class State { Waiting, Running, Retrying, Paused, Done, Failed, Cancelled }

    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    val finished: Boolean get() = state == State.Done || state == State.Failed || state == State.Cancelled

    /** Running, or about to: what keeps the transfer service and its notification alive. */
    val busy: Boolean get() = state == State.Waiting || state == State.Running || state == State.Retrying
}

/** Called by a job with the file being transferred, its bytes so far and its size. */
typealias JobProgress = (name: String, done: Long, total: Long) -> Unit

/**
 * The work of a job. It may run more than once (after a pause, a dropped connection or "Riprova"), so it keeps what
 * it already did, see [io.github.thamichele.smbcommander.core.TransferLog], and carries on from there. It returns a
 * note for the end, or null.
 */
typealias TransferWork = (NasFileSystem, JobProgress) -> String?

/**
 * Copies, moves and uploads, one at a time, in the order they were asked. The queue belongs to the app, not to a
 * screen, so it keeps going while the user browses elsewhere, and [TransferService] keeps the app alive while it has
 * work, even with the app closed. A dropped connection is retried a few times, waiting longer each time.
 */
class TransferQueue(
    private val repository: NasRepository,
    /** True for errors that mean the network or the NAS went away, worth trying again. */
    private val isConnectionLost: (Throwable) -> Boolean,
    /** An error said in words. */
    private val describe: (Throwable) -> String,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong()
    private val works = ConcurrentHashMap<Long, TransferWork>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var running: Job? = null

    @Volatile
    private var pauseRequested = false

    private val _jobs = MutableStateFlow<List<TransferJob>>(emptyList())
    val jobs: StateFlow<List<TransferJob>> = _jobs.asStateFlow()

    private val _paused = MutableStateFlow(false)
    /** The user paused the queue: nothing starts until [resume]. */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _finished = MutableSharedFlow<TransferJob>(extraBufferCapacity = 64)
    /** Each job as it ends, so screens can reload the folders it touched. */
    val finished: SharedFlow<TransferJob> = _finished

    init {
        scope.launch {
            while (true) {
                // paused jobs come first again on resume: they are older than the waiting ones
                val next = if (_paused.value) null else _jobs.value.firstOrNull { it.state == TransferJob.State.Waiting }
                if (next == null) {
                    wake.receive()
                    continue
                }
                val work = works[next.id]
                if (work == null) {
                    set(next.id) { it.copy(state = TransferJob.State.Cancelled) }
                    continue
                }
                val job = launch { run(next.id, work) }
                running = job
                job.join()
                running = null
            }
        }
    }

    fun enqueue(label: String, work: TransferWork): Long {
        val id = ids.incrementAndGet()
        works[id] = work
        _jobs.update { it + TransferJob(id, label) }
        wake.trySend(Unit)
        return id
    }

    /** Stops the job running now, keeping what it did, and holds the queue until [resume]. */
    fun pause() {
        if (_jobs.value.none { it.busy }) return
        _paused.value = true
        pauseRequested = true
        if (running == null) pauseRequested = false
        running?.cancel()
    }

    fun resume() {
        _paused.value = false
        _jobs.update { list -> list.map { if (it.state == TransferJob.State.Paused) it.copy(state = TransferJob.State.Waiting) else it } }
        wake.trySend(Unit)
    }

    /** Runs a failed job again: it carries on from where it stopped. */
    fun retry(id: Long) {
        if (works[id] == null) return
        set(id) { if (it.state == TransferJob.State.Failed) it.copy(state = TransferJob.State.Waiting, error = null) else it }
        wake.trySend(Unit)
    }

    /** Stops the job running now and drops the waiting and paused ones. */
    fun cancelAll() {
        pauseRequested = false
        _paused.value = false
        _jobs.update { list ->
            list.map { if (it.state == TransferJob.State.Waiting || it.state == TransferJob.State.Paused) it.copy(state = TransferJob.State.Cancelled) else it }
        }
        running?.cancel()
    }

    /** Forgets the jobs that ended, so the queue card can close. */
    fun clearFinished() {
        _jobs.value.filter { it.finished }.forEach { works.remove(it.id) }
        _jobs.update { list -> list.filterNot { it.finished } }
    }

    private suspend fun run(id: Long, work: TransferWork) {
        var attempt = 0
        while (true) {
            set(id) { it.copy(state = TransferJob.State.Running, retryIn = 0) }
            val failure: Exception = try {
                // interruptible: cancelling interrupts the thread, which the SMB copy loops check
                val note = runInterruptible {
                    work(repository.fs()) { name, done, total -> set(id) { it.copy(current = name, done = done, total = total) } }
                }
                return end(id, TransferJob.State.Done, note = note)
            } catch (e: CancellationException) {
                return stopped(id)
            } catch (e: InterruptedException) {
                return stopped(id)
            } catch (e: Exception) {
                e
            }
            if (!isConnectionLost(failure) || attempt >= RETRY_SECONDS.size) {
                return end(id, TransferJob.State.Failed, error = describe(failure))
            }
            val wait = RETRY_SECONDS[attempt++]
            try {
                for (left in wait downTo 1) {
                    set(id) { it.copy(state = TransferJob.State.Retrying, retryIn = left, error = describe(failure)) }
                    delay(1_000)
                }
            } catch (e: CancellationException) {
                return stopped(id)
            }
        }
    }

    /** The job was cancelled: by a pause, which keeps it for later, or by the user. */
    private fun stopped(id: Long) {
        if (pauseRequested) {
            pauseRequested = false
            set(id) { it.copy(state = TransferJob.State.Paused, retryIn = 0) }
        } else {
            end(id, TransferJob.State.Cancelled)
        }
    }

    private fun end(id: Long, state: TransferJob.State, error: String? = null, note: String? = null) {
        set(id) { it.copy(state = state, error = error, note = note, retryIn = 0) }
        if (state == TransferJob.State.Done || state == TransferJob.State.Cancelled) works.remove(id)
        _jobs.value.firstOrNull { it.id == id }?.let { _finished.tryEmit(it) }
    }

    private fun set(id: Long, change: (TransferJob) -> TransferJob) =
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }

    private companion object {
        /** Waits before each new attempt after a dropped connection: about 4 minutes in all, then the job fails. */
        val RETRY_SECONDS = intArrayOf(5, 10, 20, 30, 60, 120)
    }
}
