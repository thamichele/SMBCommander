// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * ChunkCacheTest.kt - tests of the pieces of videos kept in RAM
 *
 * Version: 1.27.0
 */
package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkCacheTest {

    private val fileSize = 1_000L

    private fun chunk(start: Long, length: Int) = Chunk(start, ByteArray(length), length)

    @Test
    fun keepsOnlyStartAndEndOfTheFile() {
        val cache = ChunkCache(maxBytes = 10_000, headBytes = 100, tailBytes = 100)
        assertTrue(cache.isWorthKeeping(fileSize, 0, 50))
        assertTrue(cache.isWorthKeeping(fileSize, 880, 50))
        assertFalse(cache.isWorthKeeping(fileSize, 400, 50))
        cache.put("a", fileSize, chunk(400, 50))
        assertEquals(0, cache.size())
    }

    @Test
    fun findsThePieceHoldingAPosition() {
        val cache = ChunkCache(maxBytes = 10_000, headBytes = 100, tailBytes = 100)
        cache.put("a", fileSize, chunk(0, 64))
        cache.put("a", fileSize, chunk(936, 64))
        assertEquals(0L, cache.find("a", 63)?.start)
        assertNull(cache.find("a", 64))
        assertEquals(936L, cache.find("a", 999)?.start)
        assertNull(cache.find("b", 0))
    }

    @Test
    fun dropsTheFileUsedLeastRecently() {
        val cache = ChunkCache(maxBytes = 100, headBytes = 100, tailBytes = 0)
        cache.put("a", fileSize, chunk(0, 40))
        cache.put("b", fileSize, chunk(0, 40))
        // "a" used again: "b" becomes the oldest
        assertNotNull(cache.find("a", 0))
        cache.put("c", fileSize, chunk(0, 40))
        assertNotNull(cache.find("a", 0))
        assertNull(cache.find("b", 0))
        assertNotNull(cache.find("c", 0))
        assertEquals(80, cache.size())
    }

    @Test
    fun aSingleFileNeverGoesOverTheLimit() {
        val cache = ChunkCache(maxBytes = 100, headBytes = 1_000, tailBytes = 0)
        cache.put("a", fileSize, chunk(0, 60))
        cache.put("a", fileSize, chunk(60, 60))
        assertEquals(60, cache.size())
        assertNotNull(cache.find("a", 0))
        assertNull(cache.find("a", 60))
    }
}
