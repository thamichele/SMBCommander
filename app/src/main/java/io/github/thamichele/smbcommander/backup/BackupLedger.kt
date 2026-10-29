package io.github.thamichele.smbcommander.backup

import java.io.File

/**
 * Media already saved on the NAS, one key per line in a private file. Append-only while a backup runs, so an
 * interrupted run resumes where it stopped.
 */
class BackupLedger(private val file: File) {

    private var keys: MutableSet<String>? = null

    @Synchronized
    fun contains(key: String): Boolean = load().contains(key)

    @Synchronized
    fun add(key: String) {
        if (load().add(key)) file.appendText(key + "\n")
    }

    @Synchronized
    fun size(): Int = load().size

    @Synchronized
    fun clear() {
        keys = mutableSetOf()
        file.delete()
    }

    private fun load(): MutableSet<String> =
        keys ?: (if (file.exists()) file.readLines().filter { it.isNotBlank() }.toMutableSet() else mutableSetOf())
            .also { keys = it }
}
