/*
 * Background.kt - small helpers shared by the jobs that run beside the screen
 *
 * Lowering the priority of the current thread while a thumbnail, a video frame or a photo sort is being made, and
 * the short hash that names files in the caches. No state, no I/O.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.data

import android.os.Process
import io.github.thamichele.smbcommander.core.Integrity
import java.security.MessageDigest

/* Names in the caches only need to be unique, not secret: SHA-1 is short and fast enough. */
private const val CACHE_KEY_ALGORITHM = "SHA-1"

/**
 * atBackgroundPriority() - runs work at background priority, so the screen keeps scrolling smoothly
 * @param block the work, run on the calling thread
 *
 * The thread gets its old priority back afterwards, also when [block] fails.
 *
 * Context: blocking; meant for threads of Dispatchers.IO or Default.
 * Return: what [block] returned.
 */
inline fun <T> atBackgroundPriority(block: () -> T): T {
    val tid = Process.myTid()
    val before = Process.getThreadPriority(tid)
    Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
    try {
        return block()
    } finally {
        Process.setThreadPriority(tid, before)
    }
}

/**
 * cacheKey() - a file name made from any text, such as a NAS path with its size and date
 * @param text what the file stands for
 *
 * Return: 40 hex characters.
 */
fun cacheKey(text: String): String =
    Integrity.hex(MessageDigest.getInstance(CACHE_KEY_ALGORITHM).digest(text.toByteArray()))
