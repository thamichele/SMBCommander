/*
 * Transfers.kt - copy and move between folders of the NAS, files or whole folders
 *
 * Decides where each element goes when its name is taken (Conflict), copies through a hidden temporary name, checks
 * every copy by SHA-256 against its original and deletes an original only after its copy matched. TransferLog lets
 * a transfer cut short carry on where it stopped. Running the jobs in the background, with notifications and pause,
 * is the app's TransferQueue, not this file.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** What to do when the destination already holds an element with the same name. */
enum class Conflict {
    /** The new one gets " (2)", " (3)"...: nothing is ever overwritten. */
    KeepBoth,

    /** Files are overwritten (only after the new copy is verified); folders are merged. */
    Replace,

    /** Files already there are left alone; folders are merged, adding only what is missing. */
    Skip,
}

/**
 * What a transfer already did. A transfer run again with the same log, after a pause or a dropped connection, carries
 * on where it stopped: files finished are not copied twice, and a "keep both" target keeps the name chosen the first
 * time instead of getting a new number.
 */
class TransferLog {
    private val targets = ConcurrentHashMap<String, String>()
    private val done: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val copied: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val skippedCount = AtomicInteger()

    /** Files left alone because the destination already had them. */
    val skipped: Int get() = skippedCount.get()

    /** Files written to the destination. */
    val written: Int get() = copied.size

    internal fun target(key: String, choose: () -> String): String = targets.getOrPut(key, choose)
    internal fun knownTarget(key: String): String? = targets[key]
    internal fun isDone(key: String) = key in done
    internal fun markCopied(key: String) { copied += key; done += key }
    internal fun wasCopied(key: String) = key in copied
    internal fun markSkipped(key: String) { if (done.add(key)) skippedCount.incrementAndGet() }

    /**
     * finished() - marks an item handled by something else than [Transfers] (an upload from the phone)
     * @param key what identifies the item in this transfer
     *
     * Return: nothing.
     */
    fun finished(key: String) = markCopied(key)

    /**
     * skip() - marks an item left alone because the destination already had it
     * @param key what identifies the item in this transfer
     *
     * Return: nothing.
     */
    fun skip(key: String) = markSkipped(key)

    /**
     * has() - tells whether an item was already handled in an earlier run
     * @param key what identifies the item in this transfer
     *
     * Return: true when it was written or skipped.
     */
    fun has(key: String) = isDone(key)

    /**
     * remember() - the target of an item, chosen once for every run of the transfer
     * @param key what identifies the item in this transfer
     * @param choose works out the target the first time
     *
     * Return: the target chosen the first time.
     */
    fun remember(key: String, choose: () -> String) = target(key, choose)
}

/**
 * Copy and move between folders of the NAS, files or whole folders. A name already taken is handled as [Conflict]
 * says. Each copied file is compared with its original by SHA-256 before it takes its name, and a move deletes an
 * original only after its copy matched.
 */
object Transfers {

    /** Called with the file being copied, its bytes so far and its size. */
    fun interface Listener {
        fun onProgress(name: String, done: Long, total: Long)
    }

    private fun key(e: RemoteEntry) = e.share + "/" + e.path

    /**
     * resolve() - where an element goes in a folder that may already hold its name
     * @param fs the file system
     * @param source the element moved or copied, to never replace itself; null for something new
     * @param isDirectory the element is a folder
     * @param toShare share of the destination
     * @param toDir destination folder
     * @param name name of the element
     * @param conflict what the user chose for names already taken
     *
     * A folder never replaces a file nor the other way round, and an element never replaces itself.
     *
     * Return: the same path when free or when [conflict] replaces or merges, a numbered one to keep both.
     */
    fun resolve(
        fs: NasFileSystem,
        source: RemoteEntry?,
        isDirectory: Boolean,
        toShare: String,
        toDir: String,
        name: String,
        conflict: Conflict,
    ): String {
        val path = RemotePath.child(toDir, name)
        val existing = fs.stat(toShare, path) ?: return path
        val itself = source != null && source.share == toShare && source.path == existing.path
        val keepBoth = conflict == Conflict.KeepBoth || itself || existing.isDirectory != isDirectory
        return if (keepBoth) EditSync.nextFreePath(fs, toShare, path) else path
    }

    /**
     * fileTarget() - where a new file goes when something may already have its name
     * @param fs the file system
     * @param share the share
     * @param path the name the file would like
     * @param conflict what the user chose for names already taken
     *
     * Used by uploads from the phone and by extractions, where the file does not come from the NAS.
     *
     * Return: [path] when free or replaced, a numbered free path to keep both, or null to leave the file alone.
     */
    fun fileTarget(fs: NasFileSystem, share: String, path: String, conflict: Conflict): String? {
        val existing = fs.stat(share, path) ?: return path
        return when {
            existing.isDirectory -> EditSync.nextFreePath(fs, share, path)
            conflict == Conflict.Skip -> null
            conflict == Conflict.Replace -> path
            else -> EditSync.nextFreePath(fs, share, path)
        }
    }

    /**
     * copy() - copies an element into a folder
     * @param fs the file system
     * @param source the file or folder to copy
     * @param toShare share of the destination
     * @param toDir destination folder
     * @param listener progress of each file
     * @param conflict what to do with names already taken
     * @param log what an earlier run of the same transfer already did
     *
     * Context: blocking; stops with InterruptedException when the thread is interrupted.
     * Return: the path of the copy.
     */
    fun copy(
        fs: NasFileSystem,
        source: RemoteEntry,
        toShare: String,
        toDir: String,
        listener: Listener = Listener { _, _, _ -> },
        conflict: Conflict = Conflict.KeepBoth,
        log: TransferLog = TransferLog(),
    ): String {
        checkNotInside(source, toShare, toDir)
        val target = log.target(key(source)) { resolve(fs, source, source.isDirectory, toShare, toDir, source.name, conflict) }
        copyTo(fs, source, toShare, target, listener, conflict, log)
        return target
    }

    /**
     * move() - moves an element into a folder
     * @param fs the file system
     * @param source the file or folder to move
     * @param toShare share of the destination
     * @param toDir destination folder
     * @param listener progress of each file
     * @param conflict what to do with names already taken
     * @param log what an earlier run of the same transfer already did
     *
     * Inside one share it is a rename, instant even for big folders; between shares it is a copy, and an original is
     * deleted only once its copy is complete. With [Conflict.Skip] the files left alone stay where they were.
     *
     * Context: blocking.
     * Return: the new path of the element.
     */
    fun move(
        fs: NasFileSystem,
        source: RemoteEntry,
        toShare: String,
        toDir: String,
        listener: Listener = Listener { _, _, _ -> },
        conflict: Conflict = Conflict.KeepBoth,
        log: TransferLog = TransferLog(),
    ): String {
        checkNotInside(source, toShare, toDir)
        if (toShare == source.share && RemotePath.normalize(toDir) == RemotePath.parent(source.path)) return source.path
        // run again after the original is already gone: the earlier run finished the job
        log.knownTarget(key(source))?.let { known -> if (fs.stat(source.share, source.path) == null) return known }
        val target = log.target(key(source)) { resolve(fs, source, source.isDirectory, toShare, toDir, source.name, conflict) }
        if (toShare == source.share) {
            renameInto(fs, source, target, conflict, log)
        } else {
            copyTo(fs, source, toShare, target, listener, conflict, log)
            if (log.skipped == 0) fs.delete(source.share, source.path) else deleteCopied(fs, source, log)
        }
        return target
    }

    /* A move inside one share: renames, merging into a folder that is already there. */
    private fun renameInto(fs: NasFileSystem, source: RemoteEntry, target: String, conflict: Conflict, log: TransferLog) {
        val existing = fs.stat(source.share, target)
        when {
            existing == null -> fs.rename(source.share, source.path, target, replace = false)
            source.isDirectory -> {
                for (child in fs.list(source.share, source.path)) {
                    renameInto(fs, child, childTarget(fs, source.share, target, child), conflict, log)
                }
                // emptied: the folder goes, unless something was left in it
                fs.removeIfEmpty(source.share, source.path)
            }
            conflict == Conflict.Skip -> log.markSkipped(key(source))
            else -> fs.rename(source.share, source.path, target, replace = true)
        }
    }

    /* After a move between shares with some files skipped: deletes only the originals that were copied. */
    private fun deleteCopied(fs: NasFileSystem, source: RemoteEntry, log: TransferLog) {
        if (!source.isDirectory) {
            if (log.wasCopied(key(source))) fs.delete(source.share, source.path)
            return
        }
        for (child in fs.list(source.share, source.path)) deleteCopied(fs, child, log)
        fs.removeIfEmpty(source.share, source.path)
    }

    /*
     * Where [child] goes inside the folder [target]: its own name, unless a file sits where a folder should go or the
     * other way round, which gets a numbered name instead.
     */
    private fun childTarget(fs: NasFileSystem, share: String, target: String, child: RemoteEntry): String {
        val path = RemotePath.child(target, child.name)
        val clash = fs.stat(share, path)
        return if (clash != null && clash.isDirectory != child.isDirectory) EditSync.nextFreePath(fs, share, path) else path
    }

    private fun copyTo(
        fs: NasFileSystem,
        source: RemoteEntry,
        toShare: String,
        target: String,
        listener: Listener,
        conflict: Conflict,
        log: TransferLog,
    ) {
        if (source.isDirectory) {
            if (fs.stat(toShare, target) == null) fs.createDirectory(toShare, target)
            for (child in fs.list(source.share, source.path)) {
                copyTo(fs, child, toShare, childTarget(fs, toShare, target, child), listener, conflict, log)
            }
            return
        }
        val k = key(source)
        if (log.isDone(k)) return
        val to = fileCopyTarget(fs, source, toShare, target, conflict) ?: return log.markSkipped(k)
        if (to.isEmpty()) return log.markCopied(k)
        copyVerified(fs, source, toShare, to, listener)
        log.markCopied(k)
    }

    /*
     * Where a file copy goes when [target] may be taken: null to skip it, "" when the identical file is already there
     * (copied before an interruption), otherwise the path to write.
     */
    private fun fileCopyTarget(fs: NasFileSystem, source: RemoteEntry, toShare: String, target: String, conflict: Conflict): String? {
        val existing = fs.stat(toShare, target) ?: return target
        return when (conflict) {
            Conflict.Skip -> null
            Conflict.Replace -> target
            // only met inside a folder copied again after an interruption: an identical file is the one copied
            // before the stop, anything else is kept next to it
            Conflict.KeepBoth -> if (isSameFile(fs, source, toShare, existing)) "" else EditSync.nextFreePath(fs, toShare, target)
        }
    }

    private fun isSameFile(fs: NasFileSystem, source: RemoteEntry, toShare: String, existing: RemoteEntry): Boolean =
        existing.size == source.size &&
            Integrity.remoteSha256(fs, toShare, existing.path) == Integrity.remoteSha256(fs, source.share, source.path)

    /*
     * Copies one file through a hidden temporary name, as uploads do, so a copy cut short never looks like a complete
     * file; the temporary copy takes the name [to] only once its SHA-256 matches the original.
     */
    private fun copyVerified(fs: NasFileSystem, source: RemoteEntry, toShare: String, to: String, listener: Listener) {
        val temp = RemotePath.child(RemotePath.parent(to), RemotePath.uploadTempName(RemotePath.name(to)))
        try {
            val streamed = fs.copyFile(source.share, source.path, toShare, temp) { done, total ->
                listener.onProgress(source.name, done, total)
            }
            listener.onProgress("Verifica di ${source.name}", UNKNOWN_SIZE, UNKNOWN_SIZE)
            val original = streamed ?: Integrity.remoteSha256(fs, source.share, source.path)
            if (Integrity.remoteSha256(fs, toShare, temp) != original) {
                throw Integrity.mismatch(source.name, "la copia è diversa dall'originale")
            }
            fs.rename(toShare, temp, to, replace = true)
        } catch (e: Exception) {
            runCatching { fs.delete(toShare, temp) }
            throw e
        }
    }

    private fun checkNotInside(source: RemoteEntry, toShare: String, toDir: String) {
        if (!source.isDirectory || toShare != source.share) return
        val dir = RemotePath.normalize(toDir)
        if (dir == source.path || dir.startsWith(source.path + "/")) {
            throw NasException("Non puoi mettere «${source.name}» dentro sé stessa")
        }
    }
}
