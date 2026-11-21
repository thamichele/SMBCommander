/*
 * NasErrors.kt - errors of the NAS and of the app, said in words for the user
 *
 * Turns exceptions into short Italian sentences; it never shows secrets.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.ui

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import io.github.thamichele.smbcommander.core.NasException

/**
 * readable() - an error of the NAS or of the app, said in words
 *
 * Return: a short sentence for the user, never a secret.
 */
fun Throwable.readable(): String = when (this) {
    is IllegalArgumentException, is IllegalStateException -> message ?: "Errore"
    is NasException -> message ?: "Errore del NAS"
    is SMBApiException -> when (status) {
        NtStatus.STATUS_ACCESS_DENIED -> "Permesso negato dal NAS"
        NtStatus.STATUS_OBJECT_NAME_COLLISION -> "Esiste già un elemento con questo nome"
        NtStatus.STATUS_SHARING_VIOLATION -> "Il file è in uso da un altro programma"
        NtStatus.STATUS_DISK_FULL -> "Spazio esaurito sul NAS"
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "Elemento non trovato sul NAS"
        NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> "La cartella non è vuota"
        NtStatus.STATUS_DELETE_PENDING -> "Il file è già in eliminazione sul NAS"
        NtStatus.STATUS_CANNOT_DELETE -> "Il NAS non permette di eliminarlo"
        NtStatus.STATUS_CONNECTION_DISCONNECTED,
        NtStatus.STATUS_CONNECTION_RESET,
        NtStatus.STATUS_NETWORK_NAME_DELETED -> "Connessione al NAS interrotta"
        else -> "Errore del NAS: $status"
    }
    else -> message ?: javaClass.simpleName
}

/**
 * isConnectionLost() - tells whether an error means the network or the NAS went away
 *
 * Wi-Fi lost, NAS asleep or restarting: worth retrying.
 *
 * Return: true for errors of the connection.
 */
fun Throwable.isConnectionLost(): Boolean {
    var e: Throwable? = this
    var depth = 0
    while (e != null && depth++ < 8) {
        when (e) {
            is java.io.FileNotFoundException -> return false
            is java.io.IOException, is java.util.concurrent.TimeoutException -> return true
            is SMBApiException -> if (e.status in CONNECTION_LOST) return true
        }
        e = e.cause
    }
    return false
}

private val CONNECTION_LOST = setOf(
    NtStatus.STATUS_CONNECTION_DISCONNECTED,
    NtStatus.STATUS_CONNECTION_RESET,
    NtStatus.STATUS_NETWORK_NAME_DELETED,
    NtStatus.STATUS_IO_TIMEOUT,
    NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
    NtStatus.STATUS_USER_SESSION_DELETED,
)
