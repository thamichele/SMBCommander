package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePathTest {

    @Test
    fun normalizesSlashes() {
        assertEquals("a/b/c", RemotePath.normalize("\\a\\b//c/"))
        assertEquals("", RemotePath.normalize("/"))
        assertEquals("a\\b", RemotePath.toSmb("/a/b/"))
    }

    @Test
    fun childParentName() {
        assertEquals("foto", RemotePath.child("", "foto"))
        assertEquals("foto/2026", RemotePath.child("foto/", "2026"))
        assertEquals("foto", RemotePath.parent("foto/2026"))
        assertEquals("", RemotePath.parent("foto"))
        assertEquals("2026", RemotePath.name("foto/2026"))
    }

    @Test
    fun extensions() {
        assertEquals("jpg", RemotePath.extension("IMG_001.JPG"))
        assertEquals("gz", RemotePath.extension("backup.tar.gz"))
        assertEquals("", RemotePath.extension(".profile"))
        assertEquals("", RemotePath.extension("README"))
        assertEquals("", RemotePath.extension("strano."))
    }

    @Test
    fun suffixes() {
        assertEquals("conti (copia).xlsx", RemotePath.withSuffix("conti.xlsx", "copia"))
        assertEquals("Makefile (copia)", RemotePath.withSuffix("Makefile", "copia"))
        assertEquals(".bashrc (copia)", RemotePath.withSuffix(".bashrc", "copia"))
    }

    @Test
    fun validNames() {
        assertTrue(RemotePath.isValidName("Foto 2026"))
        assertFalse(RemotePath.isValidName("a/b"))
        assertFalse(RemotePath.isValidName(".."))
        assertFalse(RemotePath.isValidName("  "))
        assertFalse(RemotePath.isValidName("fine."))
    }
}
