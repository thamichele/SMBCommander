// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * SmbDataSource.kt - lets the video player read a video on the NAS in place
 *
 * The player asks for small pieces; this reads the NAS in chunks that grow
 * while the reading goes straight on (256 KB at first, up to 2 MB) and asks
 * for the next chunk before the player needs it, so the NAS and the player
 * work at the same time. The start and the end of the last videos (where
 * an MP4 keeps its index) stay in RAM in a ChunkCache, so opening a video
 * again starts at once. Nothing of a video is ever written to the phone.
 *
 * Version: 1.27.0
 */
package io.github.thamichele.smbcommander.data

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.thamichele.smbcommander.core.Chunk
import io.github.thamichele.smbcommander.core.ChunkCache
import io.github.thamichele.smbcommander.core.RandomReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.min

/**
 * Data source of the video player for "nas://share/path" addresses: reads the file over SMB, a chunk ahead of the
 * player. One instance serves one player loader thread, as Media3 expects.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(private val repository: NasRepository) : BaseDataSource(true) {

    private var reader: RandomReader? = null
    private var uri: Uri? = null
    private var fileKey = ""
    private var fileSize = 0L
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    /* The chunk the player is reading, the one being fetched after it, and how big the next one will be. */
    private var chunk: Chunk? = null
    private var ahead: Future<Chunk?>? = null
    private var aheadStart = -1L
    private var chunkSize = FIRST_CHUNK

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val (share, path) = parse(dataSpec.uri)
        val file = try {
            repository.fs().openRandom(share, path)
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        reader = file
        if (dataSpec.position > file.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        fileSize = file.size
        // the size is part of the name: a video replaced by another one is not mistaken for it
        fileKey = "$share/$path:$fileSize"
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else file.size - position
        chunk = null
        chunkSize = FIRST_CHUNK
        opened = true
        _playing.update { it + 1 }
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val current = chunkAt(position) ?: return C.RESULT_END_OF_INPUT
        val from = (position - current.start).toInt()
        val n = minOf(length.toLong(), remaining, (current.length - from).toLong()).toInt()
        System.arraycopy(current.bytes, from, target, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    /* Private: the chunk holding [at], from memory, from the read ahead, or read now; null at the end of the file. */
    private fun chunkAt(at: Long): Chunk? {
        chunk?.takeIf { it.covers(at) }?.let { return it }
        val found = INDEX.find(fileKey, at) ?: takeAhead(at) ?: fetch(source(), at, chunkSize)
        chunk = found
        if (found != null) readAhead(found)
        return found
    }

    /* Private: the chunk fetched ahead when it starts at [at]; a jump elsewhere drops it and starts small again. */
    private fun takeAhead(at: Long): Chunk? {
        val pending = ahead ?: return null
        ahead = null
        if (aheadStart != at) {
            // still running on its own thread: its result is simply not used
            pending.cancel(false)
            chunkSize = FIRST_CHUNK
            return null
        }
        chunkSize = min(chunkSize * 2, MAX_CHUNK)
        return try {
            pending.get()
        } catch (e: ExecutionException) {
            throw DataSourceException(e.cause ?: e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
    }

    /* Private: starts fetching the chunk after [current], unless the file ends there or it is already in memory. */
    private fun readAhead(current: Chunk) {
        val next = current.end
        if (next >= position + remaining || next >= fileSize) return
        if (INDEX.find(fileKey, next) != null || ahead != null) return
        val from = source()
        val size = chunkSize
        aheadStart = next
        ahead = POOL.submit<Chunk?> { fetch(from, next, size) }
    }

    /* What a read needs of the open file, taken once: a read ahead may still run after close(). */
    private class Source(val reader: RandomReader, val key: String, val size: Long)

    /* Private: the open file as a Source; only called between open() and close(). */
    private fun source(): Source =
        Source(reader ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED), fileKey, fileSize)

    /* Private: reads up to [size] bytes at [at] of [from], keeps them in memory if worth it; null at the end. */
    private fun fetch(from: Source, at: Long, size: Int): Chunk? {
        val wanted = min(size.toLong(), from.size - at).toInt()
        if (wanted <= 0) return null
        val bytes = ByteArray(wanted)
        var filled = 0
        try {
            // the NAS may answer with less than asked (its own limit per request): ask again for the rest
            while (filled < wanted) {
                val n = from.reader.read(at + filled, bytes, filled, wanted - filled)
                if (n <= 0) break
                filled += n
            }
        } catch (e: Exception) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (filled == 0) return null
        return Chunk(at, bytes, filled).also { INDEX.put(from.key, from.size, it) }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        chunk = null
        val file = reader
        val pending = ahead
        reader = null
        ahead = null
        closeAfter(file, pending)
        if (opened) {
            opened = false
            _playing.update { it - 1 }
            transferEnded()
        }
    }

    /* Private: closes [file] now, or once [pending] (a read ahead on it) is over, without making the player wait. */
    private fun closeAfter(file: RandomReader?, pending: Future<Chunk?>?) {
        if (file == null) return
        if (pending == null || pending.isDone) {
            runCatching { file.close() }
            return
        }
        POOL.execute {
            // the read ahead failing or being cancelled does not matter any more: only the closing does
            runCatching { pending.get() }
            runCatching { file.close() }
        }
    }

    class Factory(private val repository: NasRepository) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(repository)
    }

    companion object {
        /* Small first read: the player gets the first bytes quickly, then the chunks grow. */
        private const val FIRST_CHUNK = 256 * 1024
        private const val MAX_CHUNK = 2 * 1024 * 1024
        private const val SCHEME = "nas"

        /* Start and end of the last videos in RAM: enough for the index of a long video, a few videos at once. */
        private const val INDEX_MAX_BYTES = 24L * 1024 * 1024
        private const val INDEX_HEAD_BYTES = 2L * 1024 * 1024
        private const val INDEX_TAIL_BYTES = 6L * 1024 * 1024

        private val INDEX = ChunkCache(INDEX_MAX_BYTES, INDEX_HEAD_BYTES, INDEX_TAIL_BYTES)

        /* Threads of the reads ahead; daemon, so they never keep the app alive. */
        private val POOL: ExecutorService = Executors.newCachedThreadPool { task ->
            Thread(task, "nas-video-read-ahead").apply { isDaemon = true }
        }

        private val _playing = MutableStateFlow(0)

        /** How many videos are being read by the player right now: other NAS work waits while it is not zero. */
        val playing: StateFlow<Int> = _playing.asStateFlow()

        /** "nas://share/folder/video.mp4", with each part escaped, for the player's MediaItem. */
        fun uri(share: String, path: String): Uri = Uri.Builder().scheme(SCHEME).authority("").apply {
            appendPath(share)
            path.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) }
        }.build()

        private fun parse(uri: Uri): Pair<String, String> {
            val parts = uri.pathSegments
            if (uri.scheme != SCHEME || parts.isEmpty()) throw DataSourceException(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
            return parts.first() to parts.drop(1).joinToString("/")
        }
    }
}
