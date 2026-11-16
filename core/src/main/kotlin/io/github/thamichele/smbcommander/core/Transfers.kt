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

    /** Marks an item handled by something else than [Transfers] (an upload from the phone). */
    fun finished(key: String) = markCopied(key)
    fun skip(key: String) = markSkipped(key)
    fun has(key: String) = isDone(key)
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
     * Where [name] goes in [toDir]: the same path when free or when [conflict] replaces or merges, a numbered one to
     * keep both. A folder never replaces a file nor the other way round, and an element never replaces itself.
     */
    fun resolve(fs: NasFileSystem, source: RemoteEntry?, isDirectory: Boolean, toShare: String, toDir: String, name: String, conflict: Conflict): String {
        val path = RemotePath.child(toDir, name)
        val existing = fs.stat(toShare, path) ?: return path
        val itself = source != null && source.share == toShare && source.path == existing.path
        return if (conflict == Conflict.KeepBoth || itself || existing.isDirectory != isDirectory) EditSync.freePath(fs, toShare, path) else path
    }

    /** Copies [source] into the folder [toDir] of [toShare] and returns the new path. */
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
     * Moves [source] into [toDir]. Inside one share it is a rename, instant even for big folders; between shares it
     * is a copy, and an original is deleted only once its copy is complete. With [Conflict.Skip] the files left
     * alone stay where they were.
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

    /** A move inside one share: renames, merging into a folder that is already there. */
    private fun renameInto(fs: NasFileSystem, source: RemoteEntry, target: String, conflict: Conflict, log: TransferLog) {
        val existing = fs.stat(source.share, target)
        when {
            existing == null -> fs.rename(source.share, source.path, target, replace = false)
            !source.isDirectory -> if (conflict == Conflict.Skip) log.markSkipped(key(source)) else fs.rename(source.share, source.path, target, replace = true)
            else -> {
                for (child in fs.list(source.share, source.path)) {
                    val childTarget = RemotePath.child(target, child.name)
                    val clash = fs.stat(source.share, childTarget)
                    val to = if (clash != null && clash.isDirectory != child.isDirectory) EditSync.freePath(fs, source.share, childTarget) else childTarget
                    renameInto(fs, child, to, conflict, log)
                }
                // emptied: the folder goes, unless something was left in it
                if (fs.stat(source.share, source.path) != null && fs.list(source.share, source.path).isEmpty()) fs.delete(source.share, source.path)
            }
        }
    }

    /** After a move between shares with some files skipped: deletes only the originals that were copied. */
    private fun deleteCopied(fs: NasFileSystem, source: RemoteEntry, log: TransferLog) {
        if (!source.isDirectory) {
            if (log.wasCopied(key(source))) fs.delete(source.share, source.path)
            return
        }
        for (child in fs.list(source.share, source.path)) deleteCopied(fs, child, log)
        if (fs.stat(source.share, source.path) != null && fs.list(source.share, source.path).isEmpty()) fs.delete(source.share, source.path)
    }

    private fun copyTo(fs: NasFileSystem, source: RemoteEntry, toShare: String, target: String, listener: Listener, conflict: Conflict, log: TransferLog) {
        if (source.isDirectory) {
            if (fs.stat(toShare, target) == null) fs.createDirectory(toShare, target)
            for (child in fs.list(source.share, source.path)) {
                val childTarget = RemotePath.child(target, child.name)
                val clash = fs.stat(toShare, childTarget)
                val to = if (clash != null && clash.isDirectory != child.isDirectory) EditSync.freePath(fs, toShare, childTarget) else childTarget
                copyTo(fs, child, toShare, to, listener, conflict, log)
            }
            return
        }
        val k = key(source)
        if (log.isDone(k)) return
        var to = target
        val existing = fs.stat(toShare, target)
        if (existing != null) {
            when (conflict) {
                Conflict.Skip -> { log.markSkipped(k); return }
                Conflict.Replace -> Unit
                // only met inside a folder copied again after an interruption: an identical file is the one copied
                // before the stop, anything else is kept next to it
                Conflict.KeepBoth -> {
                    if (existing.size == source.size && Integrity.remoteSha256(fs, toShare, target) == Integrity.remoteSha256(fs, source.share, source.path)) {
                        log.markCopied(k)
                        return
                    }
                    to = EditSync.freePath(fs, toShare, target)
                }
            }
        }
        // through a hidden temporary name, as uploads do: a copy cut short never looks like a complete file
        val temp = RemotePath.child(RemotePath.parent(to), RemotePath.uploadTempName(RemotePath.name(to)))
        try {
            val streamed = fs.copyFile(source.share, source.path, toShare, temp) { done, total -> listener.onProgress(source.name, done, total) }
            listener.onProgress("Verifica di ${source.name}", -1, -1)
            val original = streamed ?: Integrity.remoteSha256(fs, source.share, source.path)
            if (Integrity.remoteSha256(fs, toShare, temp) != original) throw Integrity.mismatch(source.name, "la copia è diversa dall'originale")
            fs.rename(toShare, temp, to, replace = true)
            log.markCopied(k)
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
