package io.github.thamichele.smbcommander.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.FaceDetector
import android.os.Process
import io.github.thamichele.smbcommander.core.PhotoKind
import io.github.thamichele.smbcommander.core.PhotoKinds
import io.github.thamichele.smbcommander.core.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Sorts photos into people, screens, text and the rest, looking at their thumbnails on the phone: faces with Android's
 * own face detector, the rest with [PhotoKinds]. Nothing leaves the phone and no model is downloaded. Each photo is
 * looked at once: the outcome is kept in a small file, by the same key as its thumbnail (path, size and date), so an
 * edited photo is looked at again.
 */
class PhotoSorter(private val file: File, private val thumbnails: ThumbnailCache) {

    private val kinds = ConcurrentHashMap<String, PhotoKind>()

    init {
        // older rules leave older files behind: they are just not read any more
        file.parentFile?.listFiles { f -> f.name.startsWith(PREFIX) && f.name != file.name }?.forEach { it.delete() }
        runCatching {
            file.forEachLine { line ->
                val tab = line.indexOf('\t')
                val kind = PhotoKind.entries.firstOrNull { it.name == line.substring(tab + 1) }
                if (tab > 0 && kind != null) kinds[line.substring(0, tab)] = kind
            }
        }
    }

    /** What [photo] shows, if it has been looked at already; videos are videos. */
    fun known(photo: RemoteEntry): PhotoKind? =
        if (photo.isVideo) PhotoKind.Video else kinds[thumbnails.key(Thumb(photo))]

    /** Looks at [photo] (its thumbnail is made first when missing) and remembers what it shows. */
    suspend fun sort(photo: RemoteEntry): PhotoKind {
        known(photo)?.let { return it }
        val thumb = thumbnails.background(photo)
        val kind = withContext(Dispatchers.Default) { lowPriority { look(thumb) } }
        val key = thumbnails.key(Thumb(photo))
        if (kinds.put(key, kind) == null) synchronized(this) { runCatching { file.appendText("$key\t${kind.name}\n") } }
        return kind
    }

    /** Forgets everything, e.g. when the app is unpaired from the NAS. */
    fun clear() {
        kinds.clear()
        synchronized(this) { file.delete() }
    }

    private fun look(thumb: File): PhotoKind {
        val decoded = BitmapFactory.decodeFile(thumb.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            ?: throw IllegalStateException("Miniatura non leggibile")
        // the face detector wants an even width
        val bitmap = if (decoded.width % 2 == 0) decoded
        else Bitmap.createScaledBitmap(decoded, decoded.width - 1, decoded.height, true).also { decoded.recycle() }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val features = PhotoKinds.features(bitmap.width, bitmap.height, pixels)
            // the face detector is the slow part: a screen does not need it
            if (PhotoKinds.isScreen(features)) return PhotoKind.Screen
            return PhotoKinds.kind(features, faces(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    private fun faces(bitmap: Bitmap): Int {
        val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val count = runCatching { FaceDetector(bitmap.width, bitmap.height, MAX_FACES).findFaces(bitmap, found) }.getOrDefault(0)
        val minEyes = minOf(bitmap.width, bitmap.height) * MIN_EYES
        return found.take(count).count { it != null && it.confidence() >= MIN_CONFIDENCE && it.eyesDistance() >= minEyes }
    }

    private inline fun <T> lowPriority(block: () -> T): T {
        val tid = Process.myTid()
        val before = Process.getThreadPriority(tid)
        Process.setThreadPriority(tid, Process.THREAD_PRIORITY_BACKGROUND)
        try {
            return block()
        } finally {
            Process.setThreadPriority(tid, before)
        }
    }

    companion object {
        private const val PREFIX = "photo-kinds-"
        val FILE_NAME = "$PREFIX${PhotoKinds.VERSION}.txt"
        private const val MAX_FACES = 4
        private const val MIN_CONFIDENCE = 0.45f
        /** Faces smaller than this (eyes closer than 3% of the picture) are background passers-by or false finds. */
        private const val MIN_EYES = 0.03f
    }
}
