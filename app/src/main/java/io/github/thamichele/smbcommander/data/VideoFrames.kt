// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * VideoFrames.kt - the few frames that animate the preview of a video in the lists
 *
 * Reads two or three frames of a video on the NAS (where VideoFramePlan
 * says), once, and keeps them in the phone's cache as small JPEGs next to
 * the thumbnails, under a size limit of their own. Only the parts of the
 * video the decoder asks for travel; nothing of the video itself is kept.
 * It does not decide when a preview is animated: that is the screen's job
 * (VideoFrameCycle.kt). The switch that turns it all off lives here too.
 *
 * Version: 1.27.0
 */
package io.github.thamichele.smbcommander.data

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.VideoFramePlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.math.roundToInt

/** Whether the previews of videos in the lists show their frames in turn. On by default: Michele asked for it. */
class VideoFramePrefs(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))

    /** True while the previews are animated; screens follow it without restarting. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /**
     * setEnabled() - turns the animated previews on or off
     * @param on the new value of the setting
     *
     * The frames already in the cache stay: turning it back on shows them at once.
     *
     * Return: nothing.
     */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    private companion object {
        const val PREFS_NAME = "video_frames"
        const val KEY_ENABLED = "animated_previews"
    }
}

/**
 * The frames of the animated previews: made once per video, then read from the phone.
 * [keyOf] names a video in the cache the same way as its thumbnail, so a changed video gets new frames.
 */
class VideoFrames(
    private val dir: File,
    private val repository: NasRepository,
    private val keyOf: (RemoteEntry) -> String,
) {

    /* Two videos at a time at most: the NAS serves the thumbnails of the screen first. */
    private val permits = Semaphore(MAX_AT_ONCE)

    /* One maker per video: a preview that leaves and comes back while its frames are made waits for them. */
    private val making = ConcurrentHashMap<String, Mutex>()

    /* Videos whose frames could not be read during this run: asked once, not at every scroll. */
    private val unreadable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val created = AtomicInteger()

    /**
     * get() - the frames of the animated preview of a video, made first if needed
     * @param video a video on the NAS (RemoteEntry.isVideo)
     *
     * While a video plays, new frames wait: the player needs the NAS more.
     *
     * Context: suspends; cancelling stops the reads on the NAS between two frames.
     * Return: the frames in the order to show them, empty when the video cannot be read.
     */
    suspend fun get(video: RemoteEntry): List<File> {
        val key = keyOf(video)
        cached(key)?.let { return it }
        if (key in unreadable) return emptyList()
        // the player and the frames would compete for the same connection
        SmbDataSource.playing.first { it == 0 }
        val lock = making.getOrPut(key) { Mutex() }
        try {
            lock.withLock {
                cached(key)?.let { return it }
                permits.withPermit { withContext(Dispatchers.IO) { make(video, key) } }
            }
        } finally {
            if (!lock.isLocked) making.remove(key, lock)
        }
        return cached(key) ?: emptyList()
    }

    /*
     * Private: the frames already on the phone, or null. The first frame is
     * written last, so when it is there all the others are too; one dropped
     * by trim() only shortens the cycle.
     */
    private fun cached(key: String): List<File>? {
        if (!file(key, 0).exists()) return null
        val now = System.currentTimeMillis()
        val found = ArrayList<File>(VideoFramePlan.MAX_FRAMES)
        for (i in 0 until VideoFramePlan.MAX_FRAMES) {
            val frame = file(key, i)
            if (!frame.exists()) break
            frame.setLastModified(now)
            found += frame
        }
        return found
    }

    /* Private, on the IO dispatcher: reads the frames over SMB and saves them; marks the video if it fails. */
    private suspend fun make(video: RemoteEntry, key: String) {
        val job = currentCoroutineContext()[Job]
        val frames = try {
            atBackgroundPriority { read(video) { job?.isActive != false } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a broken or unusual video simply keeps its still thumbnail
            unreadable += key
            return
        }
        if (frames.isEmpty()) {
            unreadable += key
            return
        }
        save(key, frames)
        if (created.incrementAndGet() % TRIM_EVERY == 0) trim()
    }

    /* Private: the frames of [video], scaled down; stops early when [wanted] turns false. */
    private fun read(video: RemoteEntry, wanted: () -> Boolean): List<Bitmap> =
        repository.fs().openRandom(video.share, video.path).use { reader ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ReaderDataSource(reader))
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                val frames = ArrayList<Bitmap>(VideoFramePlan.MAX_FRAMES)
                for (at in VideoFramePlan.positions(duration)) {
                    if (!wanted()) {
                        frames.forEach { it.recycle() }
                        throw CancellationException("Anteprima non più visibile")
                    }
                    frameAt(retriever, at)?.let { frames += it }
                }
                frames
            } finally {
                retriever.release()
            }
        }

    /* Private: one frame near [atMs], already small where Android can do it while decoding. */
    private fun frameAt(retriever: MediaMetadataRetriever, atMs: Long): Bitmap? {
        val atUs = atMs * US_PER_MS
        val option = MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(atUs, option, SCALED_BOX_PX, SCALED_BOX_PX)
        } else {
            retriever.getFrameAtTime(atUs, option)
        } ?: return null
        return shrink(frame)
    }

    /* Private: [frame] with its shorter side at most ThumbnailCache.THUMB_PX, like the thumbnails. */
    private fun shrink(frame: Bitmap): Bitmap {
        val scale = min(1f, ThumbnailCache.THUMB_PX.toFloat() / min(frame.width, frame.height))
        if (scale >= 1f) return frame
        val w = (frame.width * scale).roundToInt().coerceAtLeast(1)
        val h = (frame.height * scale).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(frame, w, h, true).also { if (it !== frame) frame.recycle() }
    }

    /* Private: writes the frames as JPEGs, the first one last (see cached()), and frees them. */
    private fun save(key: String, frames: List<Bitmap>) {
        dir.mkdirs()
        try {
            for (i in frames.indices.reversed()) {
                val target = file(key, i)
                val temp = File.createTempFile(target.name, PART_SUFFIX, dir)
                temp.outputStream().use { frames[i].compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                if (!temp.renameTo(target)) temp.delete()
            }
        } finally {
            frames.forEach { it.recycle() }
        }
    }

    /**
     * trim() - keeps the frames under their limit, dropping those of the videos seen least recently
     *
     * Context: blocking; call it off the main thread.
     * Return: nothing.
     */
    @Synchronized
    fun trim() {
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        val stale = System.currentTimeMillis() - STALE_PART_MS
        var total = 0L
        for (f in files) {
            // a frame being written right now is left alone; one left by a crash goes
            if (f.name.endsWith(PART_SUFFIX)) {
                if (f.lastModified() < stale) f.delete()
                continue
            }
            total += f.length()
            if (total > MAX_BYTES) f.delete()
        }
    }

    private fun file(key: String, index: Int) = File(dir, "$key-$index.jpg")

    companion object {
        /** Folder of the frames inside the image cache. */
        const val FOLDER = "frames"

        /* About 30 KB a frame, three a video: the frames of about a thousand videos. */
        private const val MAX_BYTES = 100L * 1024 * 1024
        private const val MAX_AT_ONCE = 2
        private const val JPEG_QUALITY = 75
        private const val TRIM_EVERY = 25
        private const val US_PER_MS = 1_000L
        private const val PART_SUFFIX = ".part"
        private const val STALE_PART_MS = 60_000L

        /* Box the decoder scales into, keeping the shape; shrink() then sets the exact size. */
        private const val SCALED_BOX_PX = 1_024
    }
}
