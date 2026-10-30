package io.github.thamichele.smbcommander.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.thamichele.smbcommander.core.NasServer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

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
    }
}
