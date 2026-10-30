package io.github.thamichele.smbcommander.ui

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import io.github.thamichele.smbcommander.core.NasException

/** An error of the NAS or of the app, said in words. */
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
