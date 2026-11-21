/*
 * NasDocumentsProvider.kt - the NAS in Android's file picker, for other apps
 *
 * Shows only the folders added in the app. Reading streams from the NAS; writing goes through a private copy uploaded
 * with the usual checks when the other app closes it.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.data

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import io.github.thamichele.smbcommander.R
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.EditSync
import io.github.thamichele.smbcommander.core.FileTrash
import io.github.thamichele.smbcommander.core.NasFileSystem
import io.github.thamichele.smbcommander.core.RandomReader
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.NasShares
import io.github.thamichele.smbcommander.core.RemotePath
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

/**
 * The NAS in Android's file picker: other apps (mail, a PDF reader, an office app) open and save files on the NAS
 * directly, choosing "SMBCommander" in the picker. Only the folders added in the app are shown, as on the start
 * screen; nothing else of the NAS is reachable from here.
 *
 * Reading streams from the NAS without downloading the whole file first. Writing goes to a private copy that is
 * uploaded, through the transfer queue with its checks and retries, when the other app closes it.
 */
class NasDocumentsProvider : DocumentsProvider() {

    private val app get() = context!!.applicationContext as SmbCommanderApp
    /** "<application id>.documents", as in the manifest: the debug build has its own. */
    private val authority get() = context!!.packageName + ".documents"
    private val handler by lazy { Handler(HandlerThread("nas-documents").apply { start() }.looper) }

    override fun onCreate(): Boolean = true

    // --- ids: "root", or "n|share|path" for an element of a saved folder ---------------------------------------------

    private fun idOf(share: String, path: String) = "n|$share|${RemotePath.normalize(path)}"

    private fun parse(id: String): Pair<String, String> {
        if (!id.startsWith("n|") || !app.extras.pickerOpen()) throw FileNotFoundException(id)
        val rest = id.removePrefix("n|")
        val share = rest.substringBefore('|')
        val path = RemotePath.normalize(rest.substringAfter('|', ""))
        if (share.isEmpty() || !allowed(share, path)) throw FileNotFoundException(id)
        return share to path
    }

    /** Only inside the folders the user added, and never inside the bins of the app. */
    private fun allowed(share: String, path: String): Boolean {
        val p = RemotePath.normalize(path)
        if (p.split('/').any { it == ".." } || FileTrash.isInside(p)) return false
        return app.folders.all().any { it.contains(share, p) }
    }

    private fun isSavedFolder(share: String, path: String) =
        app.folders.all().any { it.share.equals(share, ignoreCase = true) && RemotePath.normalize(it.path) == RemotePath.normalize(path) }

    private fun fs(): NasFileSystem = app.repository.fs()

    // --- queries -------------------------------------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_COLUMNS)
        // with the app lock on, the NAS is in the picker only for a while after the app was unlocked
        if (!app.repository.isConfigured || !app.extras.pickerOpen()) return cursor
        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT)
            add(Root.COLUMN_DOCUMENT_ID, ROOT)
            add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
            add(Root.COLUMN_SUMMARY, "Le cartelle del NAS")
            add(Root.COLUMN_ICON, R.drawable.ic_notification)
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (documentId == ROOT) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, ROOT)
                add(Document.COLUMN_DISPLAY_NAME, context!!.getString(R.string.app_name))
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, 0)
            }
            return cursor
        }
        val (share, path) = parse(documentId)
        val entry = net { it.stat(share, path) } ?: throw FileNotFoundException(documentId)
        addRow(cursor, entry)
        return cursor
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_COLUMNS)
        if (parentDocumentId == ROOT) {
            if (!app.extras.pickerOpen()) return cursor
            for (folder in app.folders.all()) {
                cursor.newRow().apply {
                    add(Document.COLUMN_DOCUMENT_ID, idOf(folder.share, folder.path))
                    add(Document.COLUMN_DISPLAY_NAME, folder.name)
                    add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                    // the folder of a whole other NAS only lists its shares: nothing can be created in it
                    add(Document.COLUMN_FLAGS, if (folder.isNas) 0 else Document.FLAG_DIR_SUPPORTS_CREATE)
                    add(Document.COLUMN_SUMMARY, folder.shown)
                }
            }
            return cursor
        }
        val (share, path) = parse(parentDocumentId)
        net { it.list(share, path) }
            .filterNot { it.name.startsWith(".") || it.name.startsWith("@") || it.name.startsWith("#") }
            .forEach { addRow(cursor, it) }
        cursor.setNotificationUri(context!!.contentResolver, DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId))
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT) return documentId.startsWith("n|")
        val (pShare, pPath) = runCatching { parse(parentDocumentId) }.getOrNull() ?: return false
        val (share, path) = runCatching { parse(documentId) }.getOrNull() ?: return false
        // the folder of another NAS holds every share of that NAS
        if (NasShares.isRoot(pShare)) return NasShares.nasOf(share) == NasShares.nasOf(pShare)
        return pShare.equals(share, ignoreCase = true) && (pPath.isEmpty() || path.startsWith("$pPath/"))
    }

    private fun addRow(cursor: MatrixCursor, e: RemoteEntry) {
        // a whole share (listed inside the folder of another NAS) is never deleted or renamed from here
        val saved = e.isDirectory && (e.path.isEmpty() || isSavedFolder(e.share, e.path))
        val flags = if (e.isDirectory) {
            Document.FLAG_DIR_SUPPORTS_CREATE or if (saved) 0 else (Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME)
        } else {
            Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, idOf(e.share, e.path))
            add(Document.COLUMN_DISPLAY_NAME, e.name)
            add(Document.COLUMN_MIME_TYPE, if (e.isDirectory) Document.MIME_TYPE_DIR else mimeOf(e.name))
            add(Document.COLUMN_SIZE, if (e.isDirectory) null else e.size)
            add(Document.COLUMN_LAST_MODIFIED, e.modified.takeIf { it > 0 })
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    // --- open ----------------------------------------------------------------------------------------------------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val (share, path) = parse(documentId)
        val entry = net { it.stat(share, path) }
        if (entry?.isDirectory == true) throw FileNotFoundException(documentId)
        val writing = mode.contains('w') || mode.contains('a')
        if (!writing) {
            entry ?: throw FileNotFoundException(documentId)
            val reader = net { it.openRandom(share, path) }
            val storage = context!!.getSystemService(StorageManager::class.java)
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Streamer(reader), handler)
        }
        return openForWriting(share, path, entry, mode)
    }

    /** Reads served straight from the NAS, at whatever position the other app asks. */
    private class Streamer(private val reader: RandomReader) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = reader.size

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            var done = 0
            try {
                while (done < size) {
                    val n = reader.read(offset + done, data, done, size - done)
                    if (n <= 0) break
                    done += n
                }
            } catch (e: Exception) {
                throw ErrnoException("read", OsConstants.EIO)
            }
            return done
        }

        override fun onRelease() {
            runCatching { reader.close() }
        }
    }

    /**
     * A private copy the other app writes into; when it closes the file and the copy changed, it goes to the NAS.
     * If the NAS file changed meanwhile (edited from somewhere else), the copy is saved next to it instead.
     */
    private fun openForWriting(share: String, path: String, entry: RemoteEntry?, mode: String): ParcelFileDescriptor {
        val dir = File(context!!.cacheDir, "documents/" + UUID.randomUUID()).apply { mkdirs() }
        val local = File(dir, RemotePath.name(path).ifEmpty { "file" })
        val truncate = mode.contains('t') || (mode == "w")
        if (entry != null && !truncate) {
            local.outputStream().use { out -> net { it.download(share, path, out) } }
        } else {
            local.createNewFile()
        }
        val before = EditSync.sha256(local)
        val baseModified = entry?.modified ?: 0L
        return ParcelFileDescriptor.open(local, ParcelFileDescriptor.parseMode(mode), handler) { error ->
            val changed = error == null && EditSync.sha256(local) != before
            if (!changed) {
                dir.deleteRecursively()
                return@open
            }
            val name = RemotePath.name(path)
            app.transfers.enqueue("Salva «$name» da un'altra app") { fs, progress ->
                val now = fs.stat(share, path)
                val target = if (now != null && baseModified > 0 && now.modified != baseModified) {
                    EditSync.freeCopyPath(fs, share, path, "modificato")
                } else {
                    path
                }
                EditSync.safeOverwrite(fs, share, target, local) { done, total -> progress(name, done, total) }
                dir.deleteRecursively()
                notifyParent(share, path)
                if (target != path) "il file sul NAS era cambiato: salvato come «${RemotePath.name(target)}»" else null
            }
        }
    }

    // --- changes -------------------------------------------------------------------------------------------------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val (share, dir) = parse(parentDocumentId)
        val name = displayName.trim()
        if (!RemotePath.isValidName(name)) throw FileNotFoundException("nome non valido")
        val id = net { fs ->
            val path = EditSync.freePath(fs, share, RemotePath.child(dir, withExtension(name, mimeType)))
            if (mimeType == Document.MIME_TYPE_DIR) fs.createDirectory(share, path)
            else fs.upload(share, path, ByteArray(0).inputStream(), 0)
            idOf(share, path)
        }
        notifyParent(share, RemotePath.child(dir, name))
        return id
    }

    /** Deleting from another app moves to the bin of the share, as deleting in the app does. */
    override fun deleteDocument(documentId: String) {
        val (share, path) = parse(documentId)
        if (path.isEmpty() || isSavedFolder(share, path)) throw FileNotFoundException("cartella salvata")
        net { fs ->
            val entry = fs.stat(share, path) ?: throw FileNotFoundException(documentId)
            FileTrash.discard(fs, entry)
        }
        notifyParent(share, path)
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (share, path) = parse(documentId)
        if (path.isEmpty() || isSavedFolder(share, path)) throw FileNotFoundException("cartella salvata")
        val name = displayName.trim()
        if (!RemotePath.isValidName(name)) throw FileNotFoundException("nome non valido")
        val target = RemotePath.child(RemotePath.parent(path), name)
        net { fs ->
            if (fs.stat(share, target) != null) throw FileNotFoundException("nome già usato")
            fs.rename(share, path, target, replace = false)
        }
        notifyParent(share, path)
        return idOf(share, target)
    }

    private fun notifyParent(share: String, path: String) {
        val parent = RemotePath.parent(path)
        val uri = DocumentsContract.buildChildDocumentsUri(authority, idOf(share, parent))
        context?.contentResolver?.notifyChange(uri, null)
        app.nasChanged.tryEmit(io.github.thamichele.smbcommander.NasChange(share, parent))
    }

    /** Calls to the NAS: an error becomes "not found" for the other app, which is what the system expects. */
    private fun <T> net(block: (NasFileSystem) -> T): T = try {
        block(fs())
    } catch (e: FileNotFoundException) {
        throw e
    } catch (e: Exception) {
        throw FileNotFoundException(e.message ?: "NAS non raggiungibile")
    }

    companion object {
        private const val ROOT = "root"

        private val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        private val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS, Document.COLUMN_SUMMARY,
        )

        /**
         * mimeOf() - the MIME type of a file, from its name
         * @param name the file name
         *
         * Return: the type Android knows for its extension, or "application/octet-stream".
         */
        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(RemotePath.extension(name).lowercase()) ?: "application/octet-stream"

        /** "Relazione" saved as PDF becomes "Relazione.pdf": the picker gives the name without the extension. */
        private fun withExtension(name: String, mime: String): String {
            if (mime == Document.MIME_TYPE_DIR || RemotePath.extension(name).isNotEmpty()) return name
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: return name
            return "$name.$ext"
        }
    }
}
