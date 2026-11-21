/*
 * BackupLedger.kt - the private list of photos already saved on the NAS by the backup
 *
 * One key per line in a file of the phone, appended while a backup runs, so an interrupted run resumes where it
 * stopped. It does not know the NAS: a reinstall starts it empty and BackupPlacement recognises what is already there.
 *
 * Version: 1.28.3
 */
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
