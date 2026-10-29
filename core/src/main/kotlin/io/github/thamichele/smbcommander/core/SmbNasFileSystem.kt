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
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * [NasFileSystem] over SMB 2/3 with smbj. Keeps one authenticated session and reconnects on demand.
 */
class SmbNasFileSystem(private val server: NasServer) : NasFileSystem {

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(20, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            // Android ships a stripped BouncyCastle: use the full one bundled with the app
            .withSecurityProvider(BCSecurityProvider())
            .withDfsEnabled(false)
            .build()
    )
    private val lock = Any()
    private var connection: Connection? = null
    private var session: Session? = null
    private val shares = HashMap<String, DiskShare>()

    /** Connects and authenticates, so wrong credentials surface right away. */
    fun connect() {
        session()
    }

    override fun listShares(): List<String> = withRetry {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session())
        ServerService(transport).shares1
            // low bits 0 = disk share; the "special" bit marks administrative shares such as ADMIN$ and C$
            .filter { (it.type and 0xFFFF) == 0 && (it.type and STYPE_SPECIAL) == 0 }
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

    override fun openRead(share: String, path: String): InputStream = withRetry {
        val file = disk(share).openFile(
            RemotePath.toSmb(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        object : java.io.FilterInputStream(file.inputStream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    file.close()
                }
            }
        }
    }

    override fun download(share: String, path: String, out: OutputStream, progress: Progress) {
        val total = stat(share, path)?.size ?: -1L
        openRead(share, path).use { input -> copy(input, out, total, progress) }
    }

    override fun upload(share: String, path: String, input: InputStream, length: Long, progress: Progress) {
        withRetry {
            disk(share).openFile(
                RemotePath.toSmb(path),
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )
        }.use { file ->
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
            conn.authenticate(
                AuthenticationContext(server.username, server.password.toCharArray(), server.domain)
            ).also { session = it }
        } catch (e: SMBApiException) {
            disconnect()
            throw NasException(
                if (e.status == NtStatus.STATUS_LOGON_FAILURE) "Utente o password non corretti" else "Accesso negato: ${e.status}",
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
        const val STYPE_SPECIAL = 0x80000000.toInt()
        val NOT_FOUND = setOf(
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
            NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
            NtStatus.STATUS_NO_SUCH_FILE,
        )
    }
}
