/*
 * PhoneUploads.kt - files and folders of the phone sent to the NAS as jobs of the transfer queue
 *
 * Each file goes through EditSync.safeUpload with its SHA-256 check; a job run again skips what is already sent.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import io.github.thamichele.smbcommander.core.Conflict
import io.github.thamichele.smbcommander.core.EditSync
import io.github.thamichele.smbcommander.core.NasFileSystem
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.core.TransferLog
import io.github.thamichele.smbcommander.core.Transfers

/**
 * Files and folders of the phone going to the NAS, as jobs of the transfer queue. Each file goes through
 * [EditSync.safeUpload]: a hidden temporary name, read back and compared by SHA-256 before it takes its name.
 * A job run again after a pause or a dropped connection skips the files already sent, see [TransferLog].
 */
object PhoneUploads {

    /** Something picked on the phone: a document, or a whole folder. */
    sealed interface Item {
        val name: String
    }

    data class FileItem(val uri: Uri, override val name: String, val size: Long) : Item

    data class FolderItem(val tree: Uri, val documentId: String, override val name: String) : Item

    /**
     * file() - a document picked with the system picker
     * @param resolver to ask its name and size
     * @param uri the document
     *
     * Return: the item to send.
     */
    fun file(resolver: ContentResolver, uri: Uri): FileItem {
        val (name, size) = resolver.describe(uri)
        return FileItem(uri, name, size)
    }

    /**
     * folder() - a folder picked with the system folder picker
     * @param resolver to ask its name
     * @param tree the folder
     *
     * Return: the item to send.
     */
    fun folder(resolver: ContentResolver, tree: Uri): FolderItem {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val name = resolver.describe(DocumentsContract.buildDocumentUriUsingTree(tree, id)).first
        return FolderItem(tree, id, name)
    }

    /**
     * send() - sends files and folders of the phone into a folder of the NAS
     * @param resolver to read them
     * @param fs the file system
     * @param items what to send
     * @param share the share of the destination
     * @param dir the destination folder
     * @param conflict what to do with names already taken
     * @param log what an earlier run of the same job already did
     * @param progress the file being sent and its bytes
     *
     * Context: blocking; stops with InterruptedException when the thread is interrupted.
     * Return: a note on the files left alone, or null when none.
     */
    fun send(
        resolver: ContentResolver,
        fs: NasFileSystem,
        items: List<Item>,
        share: String,
        dir: String,
        conflict: Conflict,
        log: TransferLog,
        progress: JobProgress,
    ): String? {
        for (item in items) {
            when (item) {
                is FileItem -> sendFile(resolver, fs, item, share, RemotePath.child(dir, item.name), conflict, log, progress)
                is FolderItem -> sendFolder(resolver, fs, item, share, RemotePath.child(dir, item.name), conflict, log, progress)
            }
        }
        return when (log.skipped) {
            0 -> null
            1 -> "1 file già presente, lasciato com'era"
            else -> "${log.skipped} file già presenti, lasciati com'erano"
        }
    }

    private fun sendFile(
        resolver: ContentResolver,
        fs: NasFileSystem,
        item: FileItem,
        share: String,
        path: String,
        conflict: Conflict,
        log: TransferLog,
        progress: JobProgress,
    ) {
        val key = item.uri.toString()
        if (log.has(key)) return
        // chosen once: a job run again writes to the same name, it does not number a second copy
        val target = log.remember(key) { Transfers.fileTarget(fs, share, path, conflict).orEmpty() }
        if (target.isEmpty()) {
            log.skip(key)
            return
        }
        // a free name taken now can only be this file, sent before the job stopped
        if (target != path && fs.stat(share, target) != null) {
            log.finished(key)
            return
        }
        EditSync.safeUpload(fs, share, target, item.size, {
            resolver.openInputStream(item.uri) ?: error("Impossibile leggere «${item.name}»")
        }) { done, total -> progress(item.name, done, total) }
        log.finished(key)
    }

    private fun sendFolder(
        resolver: ContentResolver,
        fs: NasFileSystem,
        item: FolderItem,
        share: String,
        path: String,
        conflict: Conflict,
        log: TransferLog,
        progress: JobProgress,
    ) {
        val key = DocumentsContract.buildDocumentUriUsingTree(item.tree, item.documentId).toString()
        val target = log.remember(key) {
            val existing = fs.stat(share, path)
            when {
                existing == null -> path
                // replace and skip merge into the folder already there
                existing.isDirectory && conflict != Conflict.KeepBoth -> path
                else -> EditSync.freePath(fs, share, path)
            }
        }
        if (fs.stat(share, target) == null) fs.createDirectory(share, target)
        progress(item.name, -1, -1)
        for (child in children(resolver, item)) {
            if (Thread.interrupted()) throw InterruptedException()
            when (child) {
                is FileItem -> sendFile(resolver, fs, child, share, RemotePath.child(target, child.name), conflict, log, progress)
                is FolderItem -> sendFolder(resolver, fs, child, share, RemotePath.child(target, child.name), conflict, log, progress)
            }
        }
    }

    private fun children(resolver: ContentResolver, folder: FolderItem): List<Item> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(folder.tree, folder.documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val items = ArrayList<Item>()
        resolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1)?.takeIf { RemotePath.isValidName(it) } ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    items += FolderItem(folder.tree, id, name)
                } else {
                    val size = if (c.isNull(3)) -1L else c.getLong(3)
                    items += FileItem(DocumentsContract.buildDocumentUriUsingTree(folder.tree, id), name, size)
                }
            }
        }
        return items
    }
}
