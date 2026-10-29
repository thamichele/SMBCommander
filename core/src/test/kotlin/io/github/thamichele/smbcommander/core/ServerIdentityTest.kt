package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerIdentityTest {

    private val nas = "6e61732d6361-7361-0000-0000-000000000000"

    @Test
    fun unpairedAcceptsAnyone() {
        assertTrue(ServerIdentity.accepts("", nas))
        assertTrue(ServerIdentity.accepts("", null))
    }

    @Test
    fun pairedAcceptsOnlyTheSameNas() {
        assertTrue(ServerIdentity.accepts(nas, nas))
        assertTrue(ServerIdentity.accepts(nas, nas.uppercase()))
        assertFalse(ServerIdentity.accepts(nas, "00112233-4455-6677-8899-aabbccddeeff"))
    }

    @Test
    fun pairedRefusesAServerWithoutIdentity() {
        assertFalse(ServerIdentity.accepts(nas, null))
    }

    @Test
    fun passwordStaysOutOfToString() {
        val server = NasServer("nas.example", "utente", "segreto")
        assertFalse(server.toString().contains("segreto"))
    }
}
