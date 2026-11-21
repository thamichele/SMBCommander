/*
 * ReaderDataSource.kt - lets Android's media classes read a NAS file in place
 *
 * Only the parts they ask for travel. Opening and closing the reader is the caller's job.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.data

import android.media.MediaDataSource
import io.github.thamichele.smbcommander.core.RandomReader

/** Lets Android's media classes read a NAS file in place: only the parts they ask for travel. */
class ReaderDataSource(private val reader: RandomReader) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
        if (position >= reader.size) -1 else reader.read(position, buffer, offset, size)

    override fun getSize(): Long = reader.size

    // the reader is closed by whoever opened it
    override fun close() {}
}
