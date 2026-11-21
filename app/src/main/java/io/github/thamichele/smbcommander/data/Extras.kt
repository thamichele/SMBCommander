/*
 * Extras.kt - the optional functions, all off until turned on in the settings
 *
 * App lock, Wake-on-LAN, finding the NAS again, videos that start by themselves, and the like. Kept on the phone
 * only.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import io.github.thamichele.smbcommander.core.NasException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The optional functions, all off until turned on in the settings: app lock, Wake-on-LAN, finding the NAS again
 * when its address changes, videos that start by themselves. Kept on the phone only, outside the backups like the
 * rest of the settings.
 */
class Extras(context: Context) {

    private val prefs = context.getSharedPreferences("extras", Context.MODE_PRIVATE)

    /** Fingerprint, face or screen lock to open the app. */
    var lock: Boolean
        get() = prefs.getBoolean("lock", false)
        set(value) = prefs.edit().putBoolean("lock", value).apply()

    /** When the app was last unlocked or last left while unlocked: other apps reach the NAS for a while after. */
    var lastUnlocked: Long
        get() = prefs.getLong("last_unlocked", 0)
        set(value) = prefs.edit().putLong("last_unlocked", value).apply()

    /**
     * pickerOpen() - tells whether other apps may use the NAS through the file picker
     * @param now the time to check against
     *
     * Return: true always without the lock, else only shortly after the app was unlocked.
     */
    fun pickerOpen(now: Long = System.currentTimeMillis()) = !lock || now - lastUnlocked < PICKER_WINDOW_MS

    var wakeOnLan: Boolean
        get() = prefs.getBoolean("wol", false)
        set(value) = prefs.edit().putBoolean("wol", value).apply()

    /** MAC address of the NAS network card, "00:11:22:33:44:55". */
    var wakeMac: String
        get() = prefs.getString("wol_mac", "").orEmpty()
        set(value) = prefs.edit().putString("wol_mac", value.trim()).apply()

    /** Look for the NAS on the network when it does not answer at its address any more. */
    var followNas: Boolean
        get() = prefs.getBoolean("follow", false)
        set(value) = prefs.edit().putBoolean("follow", value).apply()

    /** A video opened from a list starts by itself, without a tap on Play. */
    var autoplayVideo: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY_VIDEO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTOPLAY_VIDEO, value).apply()

    /** Under each folder of File, in small: the NAS it is on and the user the app logs in with. */
    var showNasOnFolders: Boolean
        get() = prefs.getBoolean(KEY_SHOW_NAS_ON_FOLDERS, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_NAS_ON_FOLDERS, value).apply()

    /**
     * clear() - turns every optional function off again
     *
     * Return: nothing.
     */
    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val PICKER_WINDOW_MS = 10 * 60_000L
        private const val KEY_AUTOPLAY_VIDEO = "autoplay_video"
        private const val KEY_SHOW_NAS_ON_FOLDERS = "show_nas_on_folders"
    }
}

/** Wakes a NAS that sleeps or is off, with the "magic packet" most NAS (QNAP included) understand. */
object WakeOnLan {

    /**
     * parseMac() - reads a MAC address typed by the user
     * @param mac six hex pairs, with or without separators
     *
     * Return: its six bytes, or null when it is not a MAC address.
     */
    fun parseMac(mac: String): ByteArray? {
        val parts = mac.trim().split(':', '-', '.').filter { it.isNotEmpty() }
        val hex = if (parts.size == 1) parts[0].chunked(2) else parts
        if (hex.size != 6) return null
        return runCatching { ByteArray(6) { hex[it].toInt(16).toByte() } }.getOrNull()
    }

    /**
     * wake() - sends the Wake-on-LAN packet to the NAS
     * @param mac the MAC address of the NAS
     * @param host its address, to reach its own subnet too; null when unknown
     *
     * Sent to the whole network and to the NAS's own subnet, on the two usual ports.
     *
     * Context: suspends on Dispatchers.IO.
     * Return: nothing.
     */
    suspend fun wake(mac: String, host: String?) = withContext(Dispatchers.IO) {
        val bytes = parseMac(mac) ?: throw NasException("Indirizzo MAC non valido: scrivilo come 00:11:22:33:44:55")
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(bytes, 0, packet, 6 + i * 6, 6)
        val targets = LinkedHashSet<InetAddress>()
        targets += InetAddress.getByName("255.255.255.255")
        // a.b.c.255 of the NAS address: some routers drop the global broadcast
        runCatching {
            val address = InetAddress.getByName(host ?: "")
            if (address is Inet4Address) {
                val b = address.address
                targets += InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], 0xFF.toByte()))
            }
        }
        DatagramSocket().use { socket ->
            socket.broadcast = true
            repeat(3) {
                for (target in targets) for (port in intArrayOf(9, 7)) {
                    runCatching { socket.send(DatagramPacket(packet, packet.size, target, port)) }
                }
                delay(200)
            }
        }
    }
}

/** NAS announced on the network (Bonjour / mDNS): name and address. */
data class FoundNas(val name: String, val address: String)

/** Looks for NAS on the Wi-Fi for a few seconds, through Android's own network service discovery. */
object NasFinder {

    private val TYPES = listOf("_smb._tcp.", "_qdiscover._tcp.", "_afpovertcp._tcp.")

    /**
     * discover() - looks for NAS on the local network
     * @param context for the network service discovery of Android
     * @param seconds how long to listen
     *
     * Context: suspends while listening.
     * Return: the NAS found, each once.
     */
    @Suppress("DEPRECATION")
    suspend fun discover(context: Context, seconds: Int = 5): List<FoundNas> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyList()
        val found = LinkedHashMap<String, FoundNas>()
        val pending = ArrayList<NsdServiceInfo>()
        val listeners = TYPES.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    synchronized(pending) { pending += serviceInfo }
                }
            }
        }
        listeners.forEachIndexed { i, l -> runCatching { nsd.discoverServices(TYPES[i], NsdManager.PROTOCOL_DNS_SD, l) } }
        try {
            delay(seconds * 1000L)
        } finally {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
        }
        // resolved one at a time: older Android versions refuse a second resolution while one is running
        val services = synchronized(pending) { pending.toList() }.distinctBy { it.serviceName }
        for (service in services) {
            val done = CompletableDeferred<NsdServiceInfo?>()
            runCatching {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { done.complete(null) }
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) { done.complete(serviceInfo) }
                })
            }.onFailure { done.complete(null) }
            val info = withTimeoutOrNull(2_000) { done.await() } ?: continue
            val address = info.host as? Inet4Address ?: continue
            val ip = address.hostAddress ?: continue
            found.putIfAbsent(ip, FoundNas(service.serviceName, ip))
        }
        return found.values.toList()
    }
}
