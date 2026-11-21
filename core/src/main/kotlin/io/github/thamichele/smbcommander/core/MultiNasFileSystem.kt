/*
 * MultiNasFileSystem.kt - one NasFileSystem in front of the main NAS and every other NAS the user added
 *
 * Each call is sent to the NAS its share name points to (see NasShares): plain names go to the main NAS,
 * "nas<id>:<share>" to the other NAS with that id, and the entries that come back carry the combined name again, so
 * the screens never see the difference. The root "nas<id>:" lists the shares of that NAS as folders.
 *
 * Copying between two different NAS cannot be done by either of them: the bytes are streamed through the phone and
 * hashed on the way, as for any other streamed copy. This class opens no connection itself and does not own the
 * file systems it routes to: closing it closes nothing.
 *
 * Version: 1.26.0
 */
package io.github.thamichele.smbcommander.core

import java.io.InputStream
import java.io.OutputStream

/**
 * [main] is the file system of the main NAS; [other] gives the one of another NAS by id, or null when that NAS is no
 * longer configured.
 */
class MultiNasFileSystem(
    private val main: () -> NasFileSystem,
    private val other: (nasId: String) -> NasFileSystem?,
) : NasFileSystem {

    /** Shares of the main NAS only: the others are reached through their own root folder. */
    override fun listShares(): List<String> = main().listShares()

    override fun list(share: String, path: String): List<RemoteEntry> {
        if (NasShares.isRoot(share)) return sharesOf(share)
        return target(share).list(NasShares.localOf(share), path).map { it.copy(share = share) }
    }

    override fun stat(share: String, path: String): RemoteEntry? {
        if (NasShares.isRoot(share)) {
            return if (RemotePath.normalize(path).isEmpty()) folder(share) else null
        }
        return target(share).stat(NasShares.localOf(share), path)?.copy(share = share)
    }

    override fun openRead(share: String, path: String): InputStream =
        writable(share).openRead(NasShares.localOf(share), path)

    override fun download(share: String, path: String, out: OutputStream, progress: Progress) =
        writable(share).download(NasShares.localOf(share), path, out, progress)

    override fun upload(share: String, path: String, input: InputStream, length: Long, progress: Progress) =
        writable(share).upload(NasShares.localOf(share), path, input, length, progress)

    override fun createDirectory(share: String, path: String) =
        writable(share).createDirectory(NasShares.localOf(share), path)

    override fun delete(share: String, path: String) = writable(share).delete(NasShares.localOf(share), path)

    override fun rename(share: String, from: String, to: String, replace: Boolean) =
        writable(share).rename(NasShares.localOf(share), from, to, replace)

    override fun openRandom(share: String, path: String): RandomReader =
        writable(share).openRandom(NasShares.localOf(share), path)

    override fun space(share: String): DiskSpace? =
        if (NasShares.isRoot(share)) null else target(share).space(NasShares.localOf(share))

    /**
     * copyFile() - copies one file, also from one NAS to another
     * @param fromShare share of the file to copy
     * @param from its path
     * @param toShare share of the copy
     * @param to path of the copy, created or truncated
     * @param progress bytes copied so far and the total
     *
     * On the same NAS the NAS copies the bytes itself where it can. Between two NAS the bytes always cross the phone.
     *
     * Context: blocking.
     * Return: the SHA-256 of the bytes streamed through the phone, or null when a NAS copied them.
     */
    override fun copyFile(fromShare: String, from: String, toShare: String, to: String, progress: Progress): String? {
        val source = writable(fromShare)
        val dest = writable(toShare)
        if (NasShares.nasOf(fromShare) == NasShares.nasOf(toShare)) {
            return source.copyFile(NasShares.localOf(fromShare), from, NasShares.localOf(toShare), to, progress)
        }
        val total = source.stat(NasShares.localOf(fromShare), from)?.size ?: UNKNOWN_SIZE
        progress(0, total)
        return Integrity.HashingInputStream(source.openRead(NasShares.localOf(fromShare), from)).use { input ->
            dest.upload(NasShares.localOf(toShare), to, input, total, progress)
            input.sha256()
        }
    }

    /* Nothing to release: the repository owns and closes the real connections. */
    override fun close() = Unit

    private fun target(share: String): NasFileSystem {
        val id = NasShares.nasOf(share) ?: return main()
        return other(id) ?: throw NasException("«${NasShares.label(share)}» non è più tra i NAS collegati")
    }

    /* Root folders of another NAS exist only in the app: no file can be read or written there. */
    private fun writable(share: String): NasFileSystem {
        if (NasShares.isRoot(share)) throw NasException(ROOT_READ_ONLY)
        return target(share)
    }

    private fun sharesOf(root: String): List<RemoteEntry> {
        val id = NasShares.nasOf(root) ?: return emptyList()
        return target(root).listShares().map { name -> folder(NasShares.of(id, name)) }
    }

    private fun folder(share: String) = RemoteEntry(share, "", isDirectory = true, size = 0, modified = 0)

    companion object {
        const val ROOT_READ_ONLY = "Qui ci sono solo le cartelle condivise del NAS: apri una di loro"
        private const val UNKNOWN_SIZE = -1L
    }
}
