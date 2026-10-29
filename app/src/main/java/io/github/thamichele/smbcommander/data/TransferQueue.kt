package io.github.thamichele.smbcommander.data

import io.github.thamichele.smbcommander.core.NasFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
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
) {
    enum class State { Waiting, Running, Done, Failed, Cancelled }

    val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    val finished: Boolean get() = state == State.Done || state == State.Failed || state == State.Cancelled
}

/** Called by a job with the file being transferred, its bytes so far and its size. */
typealias JobProgress = (name: String, done: Long, total: Long) -> Unit

/**
 * Copies, moves and uploads, one at a time, in the order they were asked. The queue belongs to the app, not to a
 * screen, so it keeps going while the user browses elsewhere.
 */
class TransferQueue(private val repository: NasRepository) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ids = AtomicLong()
    private val pending = Channel<Pair<Long, (NasFileSystem, JobProgress) -> Unit>>(Channel.UNLIMITED)
    @Volatile
    private var running: Job? = null

    private val _jobs = MutableStateFlow<List<TransferJob>>(emptyList())
    val jobs: StateFlow<List<TransferJob>> = _jobs.asStateFlow()

    private val _finished = MutableSharedFlow<TransferJob>(extraBufferCapacity = 64)
    /** Each job as it ends, so screens can reload the folders it touched. */
    val finished: SharedFlow<TransferJob> = _finished

    init {
        scope.launch {
            for ((id, work) in pending) {
                if (_jobs.value.firstOrNull { it.id == id }?.state != TransferJob.State.Waiting) continue
                val job = launch { run(id, work) }
                running = job
                job.join()
                running = null
            }
        }
    }

    fun enqueue(label: String, work: (NasFileSystem, JobProgress) -> Unit): Long {
        val id = ids.incrementAndGet()
        _jobs.update { it + TransferJob(id, label) }
        pending.trySend(id to work)
        return id
    }

    /** Stops the job running now and drops the waiting ones. */
    fun cancelAll() {
        _jobs.update { list -> list.map { if (it.state == TransferJob.State.Waiting) it.copy(state = TransferJob.State.Cancelled) else it } }
        running?.cancel()
    }

    /** Forgets the jobs that ended, so the queue card can close. */
    fun clearFinished() = _jobs.update { list -> list.filterNot { it.finished } }

    private suspend fun run(id: Long, work: (NasFileSystem, JobProgress) -> Unit) {
        set(id) { it.copy(state = TransferJob.State.Running) }
        val result = try {
            // interruptible: cancelling interrupts the thread, which the SMB copy loops check
            runInterruptible {
                work(repository.fs()) { name, done, total -> set(id) { it.copy(current = name, done = done, total = total) } }
            }
            TransferJob.State.Done to null
        } catch (e: CancellationException) {
            TransferJob.State.Cancelled to null
        } catch (e: InterruptedException) {
            TransferJob.State.Cancelled to null
        } catch (e: Exception) {
            TransferJob.State.Failed to (e.message ?: e.javaClass.simpleName)
        }
        set(id) { it.copy(state = result.first, error = result.second) }
        _jobs.value.firstOrNull { it.id == id }?.let { _finished.tryEmit(it) }
    }

    private fun set(id: Long, change: (TransferJob) -> TransferJob) =
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
}
