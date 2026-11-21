// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * ChunkCache.kt - pieces of the start and end of the last videos played, kept in RAM
 *
 * A video player needs the start of an MP4 and its index (the "moov" box,
 * often at the very end) before it shows anything. Keeping those pieces in
 * memory lets a video opened again, or a seek back to the start, begin
 * without asking the NAS. Only the start and the end of each file are kept,
 * never the whole video, and nothing is ever written to the phone's storage.
 * Pure Kotlin: whoever reads the NAS hands the pieces in.
 *
 * Version: 1.27.0
 */
package io.github.thamichele.smbcommander.core

import java.util.TreeMap

/** A piece of a file: [length] bytes of [bytes] that were at [start] in the file. */
class Chunk(val start: Long, val bytes: ByteArray, val length: Int) {

    /** First position after the piece. */
    val end: Long get() = start + length

    /**
     * covers() - tells whether a position of the file is inside this piece
     * @param position position in the file, in bytes
     *
     * Return: true when the byte at @position is in the piece.
     */
    fun covers(position: Long): Boolean = position >= start && position < end
}

/**
 * Pieces of the start ([headBytes]) and end ([tailBytes]) of the files read last, [maxBytes] in all; the files
 * used least recently go first. Safe to use from several threads.
 */
class ChunkCache(
    private val maxBytes: Long,
    private val headBytes: Long,
    private val tailBytes: Long,
) {
    init {
        require(maxBytes > 0 && headBytes >= 0 && tailBytes >= 0) { "Limiti della cache non validi" }
    }

    private class FileChunks {
        val chunks = TreeMap<Long, Chunk>()
        var bytes = 0L
    }

    /* Access order: the first file is the one used least recently. */
    private val files = LinkedHashMap<String, FileChunks>(INITIAL_FILES, LOAD_FACTOR, true)
    private var total = 0L

    /**
     * isWorthKeeping() - tells whether a piece is at the start or at the end of its file
     * @param fileSize size of the whole file, in bytes
     * @param start position of the piece
     * @param length size of the piece
     *
     * Return: true when put() would keep such a piece.
     */
    fun isWorthKeeping(fileSize: Long, start: Long, length: Int): Boolean =
        length > 0 && (start < headBytes || start + length > fileSize - tailBytes)

    /**
     * put() - keeps a piece of a file, when it is at its start or its end
     * @param file name of the file, including what changes when it changes (its size, say)
     * @param fileSize size of the whole file, in bytes
     * @param chunk the piece; its bytes are kept as they are, not copied
     *
     * A piece at a position already kept replaces nothing. Other files are
     * dropped, least recently used first, to stay under the limit.
     *
     * Return: nothing.
     */
    @Synchronized
    fun put(file: String, fileSize: Long, chunk: Chunk) {
        if (!isWorthKeeping(fileSize, chunk.start, chunk.length)) return
        if (chunk.bytes.size > maxBytes) return
        val entry = files.getOrPut(file) { FileChunks() }
        if (entry.chunks.containsKey(chunk.start)) return
        entry.chunks[chunk.start] = chunk
        entry.bytes += chunk.bytes.size
        total += chunk.bytes.size
        shrink(keep = file)
    }

    /**
     * find() - the kept piece that holds a position of a file
     * @param file name of the file, as given to put()
     * @param position position in the file, in bytes
     *
     * Return: the piece, or null when that part of the file is not kept.
     */
    @Synchronized
    fun find(file: String, position: Long): Chunk? {
        val entry = files[file] ?: return null
        val chunk = entry.chunks.floorEntry(position)?.value ?: return null
        return chunk.takeIf { it.covers(position) }
    }

    /** Bytes kept in all, for the tests. */
    @Synchronized
    fun size(): Long = total

    /* Private, under the lock: drops whole files, oldest first, never [keep], until under the limit. */
    private fun shrink(keep: String) {
        // through the entries: a get() on an access-ordered map would move the entry and break the iteration
        val entries = files.entries.iterator()
        while (total > maxBytes && entries.hasNext()) {
            val entry = entries.next()
            if (entry.key == keep) continue
            total -= entry.value.bytes
            entries.remove()
        }
        // the file being read is alone and still too big: its newest pieces wait for the next video
        val own = files[keep] ?: return
        while (total > maxBytes && own.chunks.isNotEmpty()) {
            val last = own.chunks.pollLastEntry().value
            own.bytes -= last.bytes.size
            total -= last.bytes.size
        }
    }

    private companion object {
        const val INITIAL_FILES = 8
        const val LOAD_FACTOR = 0.75f
    }
}
