/*
 * SmbNasFileSystem.kt - NasFileSystem over SMB 2/3, with the smbj library
 *
 * Keeps one authenticated session per NAS and reconnects on demand when the NAS fell asleep or the Wi-Fi changed.
 * Every message is signed and, with SMB 3, encrypted; the NAS must show the identity it was paired with before the
 * login is sent (see ServerIdentity). Routing between several NAS is MultiNasFileSystem's job, not this file's.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.core

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.SMB2GuestSigningRequiredException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * [NasFileSystem] over SMB 2/3 with smbj. Keeps one authenticated session and reconnects on demand.
 * [onPaired] receives the NAS identity the first time a server without one connects, so it can be stored.
 */
class SmbNasFileSystem(
    private val server: NasServer,
    private val onPaired: (String) -> Unit = {},
) : NasFileSystem {

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(SOCKET_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Android ships a stripped BouncyCastle: use the full one bundled with the app
            .withSecurityProvider(BCSecurityProvider())
            .withDfsEnabled(false)
            // every message is signed, so nobody on the network can alter files on their way; with SMB 3 the
            // traffic is also encrypted. NAS without SMB 3 encryption still work, signed but readable
            .withSigningRequired(true)
            .withEncryptData(true)
            .build()
    )
    private val lock = Any()
    private var connection: Connection? = null
    private var session: Session? = null
    private val shares = HashMap<String, DiskShare>()

    /** Identity of the NAS this connects to: the paired one, or the one seen at the first connection. */
    @Volatile
    var serverId: String = server.serverId
        private set

    /**
     * connect() - connects and authenticates now instead of at the first file operation
     *
     * Context: blocking.
     * Return: nothing; wrong credentials or an unknown NAS surface right away as [NasException].
     */
    fun connect() {
        session()
    }

    override fun listShares(): List<String> = withRetry {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session())
        ServerService(transport).shares1
            // low bits 0 = disk share; the "special" bit marks administrative shares such as ADMIN$ and C$
            .filter { (it.type and STYPE_MASK) == STYPE_DISKTREE && (it.type and STYPE_SPECIAL) == 0 }
            .map { it.netName }
            .sortedBy { it.lowercase() }
    }

    override fun list(share: String, path: String): List<RemoteEntry> = withRetry {
        val dir = RemotePath.normalize(path)
        disk(share).list(RemotePath.toSmb(dir))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                RemoteEntry(
                    share = share,
                    path = RemotePath.child(dir, it.fileName),
                    isDirectory = EnumWithValue.EnumUtils.isSet(
                        it.fileAttributes,
                        FileAttributes.FILE_ATTRIBUTE_DIRECTORY
                    ),
                    size = it.endOfFile,
                    modified = it.lastWriteTime.toEpochMillis(),
                )
            }
    }

    override fun stat(share: String, path: String): RemoteEntry? = withRetry {
        try {
            val info = disk(share).getFileInformation(RemotePath.toSmb(path))
            RemoteEntry(
                share = share,
                path = RemotePath.normalize(path),
                isDirectory = info.standardInformation.isDirectory,
                size = info.standardInformation.endOfFile,
                modified = info.basicInformation.lastWriteTime.toEpochMillis(),
            )
        } catch (e: SMBApiException) {
            if (e.status in NOT_FOUND) null else throw e
        }
    }

    override fun openRead(share: String, path: String): InputStream = withRetry { streamOf(openForRead(share, path)) }

    override fun download(share: String, path: String, out: OutputStream, progress: Progress) {
        // the size comes from the open file: no separate stat() round trip before the bytes start
        val file = withRetry { openForRead(share, path) }
        val total = runCatching { file.fileInformation.standardInformation.endOfFile }.getOrDefault(UNKNOWN_SIZE)
        streamOf(file).use { input -> copy(input, out, total, progress) }
    }

    override fun upload(share: String, path: String, input: InputStream, length: Long, progress: Progress) {
        withRetry { openForWrite(share, path, EnumSet.of(AccessMask.GENERIC_WRITE)) }.use { file ->
            file.outputStream.use { out -> copy(input, out, length, progress) }
        }
    }

    override fun createDirectory(share: String, path: String) = withRetry {
        disk(share).mkdir(RemotePath.toSmb(path))
    }

    override fun delete(share: String, path: String) = withRetry {
        val disk = disk(share)
        val smbPath = RemotePath.toSmb(path)
        if (disk.getFileInformation(smbPath).standardInformation.isDirectory) {
            disk.rmdir(smbPath, true)
        } else {
            disk.rm(smbPath)
        }
    }

    override fun space(share: String): DiskSpace? = withRetry {
        val info = disk(share).shareInformation
        if (info.totalSpace > 0) DiskSpace(info.totalSpace, info.callerFreeSpace) else null
    }

    override fun rename(share: String, from: String, to: String, replace: Boolean) = withRetry {
        disk(share).open(
            RemotePath.toSmb(from),
            EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        ).use { it.rename(RemotePath.toSmb(to), replace) }
    }

    override fun openRandom(share: String, path: String): RandomReader = withRetry {
        val file = openForRead(share, path)
        val length = file.fileInformation.standardInformation.endOfFile
        object : RandomReader {
            override val size: Long = length

            override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
                if (position >= size) return -1
                return file.read(buffer, position, offset, length).let { if (it <= 0) -1 else it }
            }

            override fun close() = file.close()
        }
    }

    override fun copyFile(fromShare: String, from: String, toShare: String, to: String, progress: Progress): String? {
        val total = stat(fromShare, from)?.size ?: UNKNOWN_SIZE
        progress(0, total)
        // server-side copy (FSCTL_SRV_COPYCHUNK): the NAS copies the bytes itself
        val copiedOnNas = runCatching {
            withRetry {
                openForRead(fromShare, from).use { source ->
                    openForWrite(toShare, to, EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE))
                        .use { target -> source.remoteCopyTo(target) }
                }
            }
        }.isSuccess
        if (copiedOnNas) {
            progress(total, total)
            return null
        }
        // the NAS refused (or copying between shares is not allowed): stream it through the phone
        return Integrity.HashingInputStream(openRead(fromShare, from)).use { input ->
            upload(toShare, to, input, total, progress)
            input.sha256()
        }
    }

    override fun close() {
        synchronized(lock) {
            disconnect()
            client.close()
        }
    }

    private fun session(): Session = synchronized(lock) {
        val current = session
        if (current != null && connection?.isConnected == true) return current
        disconnect()
        try {
            val conn = client.connect(server.host)
            connection = conn
            val presented = conn.connectionContext.serverGuid?.takeIf { it != NO_GUID }?.toString()
            if (!ServerIdentity.accepts(serverId, presented)) {
                // checked before authenticating: an impostor never sees the login
                disconnect()
                throw NasException(MSG_OTHER_NAS)
            }
            conn.authenticate(
                AuthenticationContext(server.username, server.password.toCharArray(), server.domain)
            ).also {
                session = it
                if (serverId.isEmpty() && presented != null) {
                    serverId = presented
                    onPaired(presented)
                }
            }
        } catch (e: SMB2GuestSigningRequiredException) {
            // the NAS let us in as guest (unknown user): a guest session cannot be signed, so it is refused
            disconnect()
            throw NasException(MSG_GUEST, e)
        } catch (e: SMBApiException) {
            disconnect()
            throw NasException(
                if (e.status == NtStatus.STATUS_LOGON_FAILURE) MSG_LOGON_FAILURE else "Accesso negato: ${e.status}",
                e
            )
        } catch (e: IOException) {
            disconnect()
            throw NasException("NAS non raggiungibile: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    private fun disk(share: String): DiskShare = synchronized(lock) {
        shares[share]?.takeIf { it.isConnected }?.let { return it }
        val disk = session().connectShare(share) as? DiskShare
            ?: throw NasException("\"$share\" non è una cartella condivisa")
        shares[share] = disk
        disk
    }

    /* Opens an existing file for reading; the caller runs it inside withRetry and closes it. */
    private fun openForRead(share: String, path: String): File = disk(share).openFile(
        RemotePath.toSmb(path),
        EnumSet.of(AccessMask.GENERIC_READ),
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OPEN,
        null
    )

    /* Opens [path] with [access], creating it or emptying it first; the caller runs it inside withRetry. */
    private fun openForWrite(share: String, path: String, access: Set<AccessMask>): File = disk(share).openFile(
        RemotePath.toSmb(path),
        access,
        null,
        SMB2ShareAccess.ALL,
        SMB2CreateDisposition.FILE_OVERWRITE_IF,
        null
    )

    /* The bytes of [file] as a stream whose close() also closes the file on the NAS. */
    private fun streamOf(file: File): InputStream = object : FilterInputStream(file.inputStream) {
        override fun close() {
            try {
                super.close()
            } finally {
                file.close()
            }
        }
    }

    private fun disconnect() {
        shares.values.forEach { runCatching { it.close() } }
        shares.clear()
        runCatching { session?.close() }
        runCatching { connection?.close(true) }
        session = null
        connection = null
    }

    /** Runs [block] once more on a fresh connection when the old one dropped (NAS asleep, Wi-Fi change). */
    private fun <T> withRetry(block: () -> T): T = try {
        block()
    } catch (e: NasException) {
        throw e
    } catch (e: SMBApiException) {
        throw e
    } catch (e: Exception) {
        synchronized(lock) { disconnect() }
        try {
            block()
        } catch (retry: SMBApiException) {
            throw retry
        } catch (retry: NasException) {
            throw retry
        } catch (retry: Exception) {
            throw NasException("Connessione al NAS interrotta: ${retry.message ?: retry.javaClass.simpleName}", retry)
        }
    }

    private fun copy(input: InputStream, out: OutputStream, total: Long, progress: Progress) {
        val buffer = ByteArray(BUFFER_SIZE)
        var done = 0L
        progress(0, total)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            done += read
            progress(done, total)
        }
        out.flush()
    }

    private companion object {
        const val BUFFER_SIZE = 1 shl 20
        const val TIMEOUT_SECONDS = 20L
        const val SOCKET_TIMEOUT_SECONDS = 60L

        /* Share types of MS-SRVS 2.2.2.4: the low bits say disk, printer, device or IPC; one high bit says special. */
        const val STYPE_MASK = 0xFFFF
        const val STYPE_DISKTREE = 0
        const val STYPE_SPECIAL = 0x80000000.toInt()

        const val MSG_OTHER_NAS = "A questo indirizzo risponde un NAS diverso da quello collegato. Se hai cambiato o " +
            "rinominato il NAS, collegalo di nuovo dalle impostazioni."
        const val MSG_GUEST = "Il NAS non riconosce l'utente: controlla utente e password"
        const val MSG_LOGON_FAILURE = "Utente o password non corretti"
        val NO_GUID = java.util.UUID(0, 0)
        val NOT_FOUND = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
        )
    }
}
