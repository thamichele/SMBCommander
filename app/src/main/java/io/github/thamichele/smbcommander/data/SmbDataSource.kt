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
import io.github.thamichele.smbcommander.core.RandomReader
import kotlin.math.min

/**
 * Lets the video player read a file on the NAS directly, in chunks, without downloading it first: seeking jumps
 * straight to the right place in the file.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(private val repository: NasRepository) : BaseDataSource(true) {

    private var reader: RandomReader? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    // one SMB request per chunk instead of one per small read of the player
    private val buffer = ByteArray(CHUNK)
    private var bufferStart = 0L
    private var bufferLength = 0

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
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else file.size - position
        bufferLength = 0
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (position < bufferStart || position >= bufferStart + bufferLength) {
            val read = try {
                reader!!.read(position, buffer, 0, min(CHUNK.toLong(), remaining).toInt())
            } catch (e: Exception) {
                throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            }
            if (read <= 0) return C.RESULT_END_OF_INPUT
            bufferStart = position
            bufferLength = read
        }
        val from = (position - bufferStart).toInt()
        val n = minOf(length.toLong(), remaining, (bufferLength - from).toLong()).toInt()
        System.arraycopy(buffer, from, target, offset, n)
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        runCatching { reader?.close() }
        reader = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    class Factory(private val repository: NasRepository) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(repository)
    }

    companion object {
        private const val CHUNK = 512 * 1024
        private const val SCHEME = "nas"

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
