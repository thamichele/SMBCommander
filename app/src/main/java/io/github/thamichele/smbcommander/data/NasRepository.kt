package io.github.thamichele.smbcommander.data

import io.github.thamichele.smbcommander.core.NasException
import io.github.thamichele.smbcommander.core.NasFileSystem
import io.github.thamichele.smbcommander.core.NasServer
import io.github.thamichele.smbcommander.core.SmbNasFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Owns the one SMB connection of the app. */
class NasRepository(private val credentials: CredentialStore) {

    @Volatile
    private var current: NasFileSystem? = null

    val isConfigured: Boolean get() = current != null || credentials.load() != null

    fun server(): NasServer? = credentials.load()

    /** Checks the settings by really connecting, then keeps them. */
    suspend fun connect(server: NasServer) = withContext(Dispatchers.IO) {
        val fs = SmbNasFileSystem(server)
        try {
            fs.connect()
        } catch (e: Exception) {
            fs.close()
            throw e
        }
        credentials.save(server)
        current?.close()
        current = fs
    }

    fun forget() {
        current?.close()
        current = null
        credentials.clear()
    }

    /** The file system, created from the saved settings on first use. */
    fun fs(): NasFileSystem = current ?: synchronized(this) {
        current ?: SmbNasFileSystem(credentials.load() ?: throw NasException("NAS non configurato")).also { current = it }
    }

    suspend fun <T> io(block: (NasFileSystem) -> T): T = withContext(Dispatchers.IO) { block(fs()) }
}
