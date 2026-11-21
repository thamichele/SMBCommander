package io.github.thamichele.smbcommander.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.thamichele.smbcommander.core.NasServer
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Another NAS added by the user next to the main one: it shows as one more folder in File. */
data class OtherNas(
    /** Letters and digits, never reused while the NAS is in the list; see NasShares. */
    val id: String,
    /** The name shown for the folder of this NAS. */
    val name: String,
    val server: NasServer,
)

/**
 * Keeps the NAS connection settings on the device. The password is encrypted with an AES key that never leaves the
 * Android Keystore; backups and device-to-device transfers are disabled (manifest and data_extraction_rules.xml), so
 * nothing is copied off the phone.
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    /** Cheap check, without decrypting the password. */
    fun hasServer(): Boolean = prefs.contains(KEY_HOST) && prefs.contains(KEY_PASSWORD)

    /** The address alone, for screens that only show it: no Keystore round trip for the password. */
    fun host(): String? = prefs.getString(KEY_HOST, null)

    /** The saved user name alone: unlike [load] it decrypts nothing, so it costs nothing on the main thread. */
    fun user(): String = prefs.getString(KEY_USER, "").orEmpty()

    fun load(): NasServer? {
        val host = prefs.getString(KEY_HOST, null) ?: return null
        val password = prefs.getString(KEY_PASSWORD, null)?.let { runCatching { decrypt(it) }.getOrNull() } ?: return null
        return NasServer(
            host = host,
            username = prefs.getString(KEY_USER, "").orEmpty(),
            password = password,
            domain = prefs.getString(KEY_DOMAIN, "").orEmpty(),
            serverId = prefs.getString(KEY_SERVER_ID, "").orEmpty(),
        )
    }

    fun save(server: NasServer) {
        prefs.edit()
            .putString(KEY_HOST, server.host)
            .putString(KEY_USER, server.username)
            .putString(KEY_DOMAIN, server.domain)
            .putString(KEY_PASSWORD, encrypt(server.password))
            .putString(KEY_SERVER_ID, server.serverId)
            .apply()
    }

    /** Remembers the identity of the NAS, learnt at the first connection after an update. */
    fun savePairing(serverId: String) {
        prefs.edit().putString(KEY_SERVER_ID, serverId).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        others.edit().clear().apply()
    }

    // --- the other NAS --------------------------------------------------------------------------------------------

    private val others = context.getSharedPreferences("other_servers", Context.MODE_PRIVATE)

    /**
     * otherNas() - the other NAS, in the order they were added
     *
     * A NAS whose password cannot be decrypted any more (the Keystore key was reset) is left out: it has to be added
     * again, as the main one would.
     *
     * Return: the list, possibly empty.
     */
    fun otherNas(): List<OtherNas> {
        val json = others.getString(KEY_LIST, null) ?: return emptyList()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> readOther(array.getJSONObject(i)) }
    }

    /** Names of the other NAS by id, without decrypting any password: what the screens need. */
    fun otherNames(): Map<String, String> {
        val json = others.getString(KEY_LIST, null) ?: return emptyMap()
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyMap()
        return (0 until array.length()).associate { i ->
            array.getJSONObject(i).let { it.getString(KEY_ID) to it.optString(KEY_NAME) }
        }
    }

    /**
     * saveOther() - adds [nas], or replaces the one with the same id
     * @param nas the NAS with its password in clear: it is encrypted here
     *
     * Return: nothing.
     */
    fun saveOther(nas: OtherNas) {
        val list = otherNas().filterNot { it.id == nas.id } + nas
        writeOthers(list)
    }

    /** Remembers the identity of another NAS, learnt at its first connection. */
    fun saveOtherPairing(id: String, serverId: String) {
        otherNas().find { it.id == id }?.let { saveOther(it.copy(server = it.server.copy(serverId = serverId))) }
    }

    fun removeOther(id: String) {
        writeOthers(otherNas().filterNot { it.id == id })
    }

    /** A fresh id: one more than the highest ever given, so a removed NAS never hands its id to a new one. */
    fun newOtherId(): String {
        val next = others.getInt(KEY_NEXT_ID, FIRST_OTHER_ID)
        others.edit().putInt(KEY_NEXT_ID, next + 1).apply()
        return next.toString()
    }

    private fun readOther(o: JSONObject): OtherNas? {
        val password = runCatching { decrypt(o.getString(KEY_PASSWORD)) }.getOrNull() ?: return null
        val server = NasServer(
            host = o.getString(KEY_HOST),
            username = o.optString(KEY_USER),
            password = password,
            domain = o.optString(KEY_DOMAIN),
            serverId = o.optString(KEY_SERVER_ID),
        )
        return OtherNas(o.getString(KEY_ID), o.optString(KEY_NAME), server)
    }

    private fun writeOthers(list: List<OtherNas>) {
        val array = JSONArray()
        list.forEach { nas ->
            array.put(
                JSONObject()
                    .put(KEY_ID, nas.id)
                    .put(KEY_NAME, nas.name)
                    .put(KEY_HOST, nas.server.host)
                    .put(KEY_USER, nas.server.username)
                    .put(KEY_DOMAIN, nas.server.domain)
                    .put(KEY_PASSWORD, encrypt(nas.server.password))
                    .put(KEY_SERVER_ID, nas.server.serverId)
            )
        }
        others.edit().putString(KEY_LIST, array.toString()).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_SIZE))
        }
        return String(cipher.doFinal(sealed, IV_SIZE, sealed.size - IV_SIZE), Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "smbcommander-credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val KEY_HOST = "host"
        const val KEY_USER = "user"
        const val KEY_DOMAIN = "domain"
        const val KEY_PASSWORD = "password"
        const val KEY_SERVER_ID = "server_id"
        const val KEY_LIST = "list"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_NEXT_ID = "next_id"
        /* The main NAS is number 1 in the user's mind: the others start from 2. */
        const val FIRST_OTHER_ID = 2
    }
}
