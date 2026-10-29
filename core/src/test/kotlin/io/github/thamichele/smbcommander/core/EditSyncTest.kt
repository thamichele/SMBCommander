package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class EditSyncTest {

    private val fs = FakeNasFileSystem()

    private fun session(local: File, remote: RemoteEntry) = EditSession(
        id = "1", share = "docs", path = remote.path, localFile = local,
        baseHash = EditSync.sha256(local), remoteSize = remote.size, remoteModified = remote.modified, openedAt = 0
    )

    private fun localCopy(content: String): File =
        File.createTempFile("edit", ".txt").apply { deleteOnExit(); writeText(content) }

    @Test
    fun unchangedLocalCopyIsNotUploaded() {
        fs.put("docs", "note.txt", "ciao")
        val local = localCopy("ciao")
        val s = session(local, fs.stat("docs", "note.txt")!!)
        assertEquals(SyncDecision.Unchanged, EditSync.decide(s, EditSync.sha256(local), fs.stat("docs", "note.txt")))
    }

    @Test
    fun localChangeOnlyIsUploaded() {
        fs.put("docs", "note.txt", "ciao")
        val local = localCopy("ciao")
        val s = session(local, fs.stat("docs", "note.txt")!!)
        local.writeText("ciao mondo")
        assertEquals(SyncDecision.Upload, EditSync.decide(s, EditSync.sha256(local), fs.stat("docs", "note.txt")))
    }

    @Test
    fun changesOnBothSidesAreAConflict() {
        fs.put("docs", "note.txt", "ciao")
        val local = localCopy("ciao")
        val s = session(local, fs.stat("docs", "note.txt")!!)
        local.writeText("dal telefono")
        fs.put("docs", "note.txt", "dal computer")
        assertTrue(EditSync.decide(s, EditSync.sha256(local), fs.stat("docs", "note.txt")) is SyncDecision.Conflict)
    }

    @Test
    fun deletedRemoteIsReported() {
        fs.put("docs", "note.txt", "ciao")
        val local = localCopy("ciao")
        val s = session(local, fs.stat("docs", "note.txt")!!)
        local.writeText("nuovo")
        fs.delete("docs", "note.txt")
        assertEquals(SyncDecision.RemoteMissing, EditSync.decide(s, EditSync.sha256(local), fs.stat("docs", "note.txt")))
    }

    @Test
    fun safeOverwriteReplacesTargetAndLeavesNoTemp() {
        fs.put("docs", "a/note.txt", "vecchio")
        val result = EditSync.safeOverwrite(fs, "docs", "a/note.txt", localCopy("nuovo"))
        assertEquals("nuovo", fs.read("docs", "a/note.txt"))
        assertEquals(5L, result.size)
        assertEquals(listOf("docs/a/note.txt"), fs.files.keys.toList())
    }

    @Test
    fun interruptedUploadKeepsOriginal() {
        fs.put("docs", "note.txt", "originale intatto")
        fs.failUploadAfter = 3
        try {
            EditSync.safeOverwrite(fs, "docs", "note.txt", localCopy("versione nuova"))
            fail("upload should fail")
        } catch (e: NasException) {
            // expected
        }
        assertEquals("originale intatto", fs.read("docs", "note.txt"))
        assertNull(fs.read("docs", RemotePath.uploadTempName("note.txt")))
    }

    @Test
    fun copyPathSkipsTakenNames() {
        fs.put("docs", "note.txt", "x")
        fs.put("docs", "note (telefono).txt", "y")
        assertEquals("note (telefono 2).txt", EditSync.freeCopyPath(fs, "docs", "note.txt", "telefono"))
    }

    @Test
    fun freePathNumbersTakenNames() {
        assertEquals("foto.jpg", EditSync.freePath(fs, "docs", "foto.jpg"))
        fs.put("docs", "foto.jpg", "x")
        fs.put("docs", "foto (2).jpg", "x")
        assertEquals("foto (3).jpg", EditSync.freePath(fs, "docs", "foto.jpg"))
    }
}
