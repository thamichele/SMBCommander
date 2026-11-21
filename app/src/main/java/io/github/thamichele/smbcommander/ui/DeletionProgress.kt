/*
 * DeletionProgress.kt - a deletion of several files as the window of DeletionDialog follows it
 *
 * Holds the state shown file by file, the request to stop after the current file, and the outcome of one file. Used
 * by the file browser, the gallery and the cleanup's bin, which before kept three copies of the same code. What to
 * delete, and how, stays with each of them.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.ui

import io.github.thamichele.smbcommander.core.RemoteEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The deletion under way, or just finished, of one screen. */
class DeletionProgress {

    private val _state = MutableStateFlow<Deletion?>(null)

    /** The deletion shown in its window; null when no window is open. */
    val state: StateFlow<Deletion?> = _state.asStateFlow()

    /** The user asked to stop: the loop ends after the file being deleted now. */
    @Volatile
    var isStopRequested = false
        private set

    /**
     * start() - gets ready for a new deletion
     *
     * Return: nothing; a stop asked for an earlier deletion is forgotten.
     */
    fun start() {
        isStopRequested = false
    }

    /**
     * show() - puts a step of the deletion in its window
     * @param next the deletion as it is now
     *
     * Return: nothing.
     */
    fun show(next: Deletion) {
        _state.value = next
    }

    /**
     * stop() - stops the deletion after the file being deleted now; the others stay where they are
     *
     * Return: nothing.
     */
    fun stop() {
        isStopRequested = true
        _state.update { it?.copy(stopping = true) }
    }

    /**
     * close() - closes the window once the deletion has finished
     *
     * Return: nothing; a deletion still running keeps its window.
     */
    fun close() {
        if (_state.value?.finished == true) _state.value = null
    }

    companion object {

        /**
         * step() - deletes one file and says how it went
         * @param entry the file
         * @param delete deletes it; false when it was no longer there
         *
         * A failure becomes a failed step, so it does not stop the others; a cancellation is passed on.
         *
         * Context: suspends while [delete] runs.
         * Return: the outcome of the file.
         */
        suspend fun step(entry: RemoteEntry, delete: suspend () -> Boolean): DeletionStep = try {
            val outcome = if (delete()) DeletionStep.Outcome.Deleted else DeletionStep.Outcome.Skipped
            DeletionStep(entry, outcome)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DeletionStep(entry, DeletionStep.Outcome.Failed, e.readable())
        }
    }
}
