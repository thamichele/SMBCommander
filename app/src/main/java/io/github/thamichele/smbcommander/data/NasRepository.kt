/*
 * NasRepository.kt - the SMB connections of the app: the main NAS and the other NAS
 *
 * Opens, keeps and closes the connections and gives every screen one file system for all of them. Storing the settings
 * is CredentialStore's job.
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.data

import io.github.thamichele.smbcommander.core.MultiNasFileSystem
import io.github.thamichele.smbcommander.core.NasException
import io.github.thamichele.smbcommander.core.NasFileSystem
import io.github.thamichele.smbcommander.core.NasServer
import io.github.thamichele.smbcommander.core.NasShares
import io.github.thamichele.smbcommander.core.SmbNasFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Owns the SMB connections of the app: the main NAS and the other NAS the user added. Every screen gets one file
 * system for all of them ([fs]): the share name tells which NAS a call goes to, see NasShares.
 */
class NasRepository(private val credentials: CredentialStore) {

    @Volatile
    private var current: NasFileSystem? = null

    /* Connections to the other NAS by id, opened on first use. */
    private val otherConnections = HashMap<String, NasFileSystem>()

    private val router = MultiNasFileSystem(main = ::mainFs, other = ::otherFs)

    init {
        NasShares.names = credentials.otherNames()
    }

    val isConfigured: Boolean get() = current != null || credentials.hasServer()

    fun server(): NasServer? = credentials.load()

    fun host(): String? = credentials.host()

    fun user(): String = credentials.user()

    /** Checks the settings by really connecting, then keeps them. Pairs again with whichever NAS answers now. */
    suspend fun connect(server: NasServer) = withContext(Dispatchers.IO) {
        val fs = SmbNasFileSystem(server.copy(serverId = ""))
        try {
            fs.connect()
        } catch (e: Exception) {
            fs.close()
            throw e
        }
        credentials.save(server.copy(serverId = fs.serverId))
        current?.close()
        current = fs
    }

    /**
     * The paired NAS answering at a new [address] (the router gave it another one): its identity is checked before
     * any login, so a different machine there never receives the password. True when it was the same NAS and the
     * new address is now saved.
     */
    suspend fun follow(address: String): Boolean = withContext(Dispatchers.IO) {
        val saved = credentials.load() ?: return@withContext false
        if (saved.serverId.isEmpty() || saved.host.equals(address, ignoreCase = true)) return@withContext false
        val fs = SmbNasFileSystem(saved.copy(host = address))
        try {
            fs.connect()
        } catch (e: Exception) {
            fs.close()
            return@withContext false
        }
        credentials.save(saved.copy(host = address))
        current?.close()
        current = fs
        true
    }

    fun forget() {
        current?.close()
        current = null
        synchronized(otherConnections) {
            otherConnections.values.forEach { it.close() }
            otherConnections.clear()
        }
        credentials.clear()
        NasShares.names = emptyMap()
    }

    /** The other NAS, in the order they were added. */
    fun otherNas(): List<OtherNas> = credentials.otherNas()

    /** Address of the NAS that holds [share], for screens that only show it. */
    fun hostOf(share: String): String? {
        val id = NasShares.nasOf(share) ?: return host()
        return credentials.otherNas().find { it.id == id }?.server?.host
    }

    /**
     * saveOther() - checks another NAS by really connecting to it, then keeps it
     * @param id the NAS to change, or null to add a new one
     * @param name the name shown for its folder
     * @param server address and credentials; an empty password keeps the saved one when address and user are the same
     *
     * Like the main NAS it is paired with whichever machine answers now, and later refused if another one answers.
     *
     * Context: suspends on the IO dispatcher.
     * Return: the saved NAS.
     */
    suspend fun saveOther(id: String?, name: String, server: NasServer): OtherNas = withContext(Dispatchers.IO) {
        val old = id?.let { known -> credentials.otherNas().find { it.id == known } }
        val keepPassword = old != null && server.password.isEmpty() &&
            old.server.host == server.host && old.server.username == server.username
        val full = if (keepPassword) server.copy(password = old!!.server.password) else server
        val fs = SmbNasFileSystem(full.copy(serverId = ""))
        try {
            fs.connect()
        } catch (e: Exception) {
            fs.close()
            throw e
        }
        val nas = OtherNas(id ?: credentials.newOtherId(), name, full.copy(serverId = fs.serverId))
        credentials.saveOther(nas)
        synchronized(otherConnections) { otherConnections.put(nas.id, fs)?.close() }
        NasShares.names = credentials.otherNames()
        nas
    }

    fun removeOther(id: String) {
        synchronized(otherConnections) { otherConnections.remove(id)?.close() }
        credentials.removeOther(id)
        NasShares.names = credentials.otherNames()
    }

    /** One file system for every NAS: the share name chooses the NAS, see NasShares. */
    fun fs(): NasFileSystem = router

    private fun otherFs(id: String): NasFileSystem? = synchronized(otherConnections) {
        otherConnections[id] ?: credentials.otherNas().find { it.id == id }?.let { nas ->
            SmbNasFileSystem(nas.server, onPaired = { credentials.saveOtherPairing(id, it) })
                .also { otherConnections[id] = it }
        }
    }

    /** The file system of the main NAS, created from the saved settings on first use. */
    private fun mainFs(): NasFileSystem = current ?: synchronized(this) {
        current ?: SmbNasFileSystem(
            credentials.load() ?: throw NasException("NAS non configurato"),
            onPaired = credentials::savePairing,
        ).also { current = it }
    }

    suspend fun <T> io(block: (NasFileSystem) -> T): T = withContext(Dispatchers.IO) { block(fs()) }
}
