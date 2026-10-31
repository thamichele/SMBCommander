package io.github.thamichele.smbcommander.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.ExifInterface
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val RemoteEntry.isImage: Boolean get() = !isDirectory && extension in ThumbnailCache.IMAGE_EXTENSIONS
val RemoteEntry.isVideo: Boolean get() = !isDirectory && extension in ThumbnailCache.VIDEO_EXTENSIONS

/** Photos and videos: what the gallery and the viewer show. */
val RemoteEntry.isMedia: Boolean get() = isImage || isVideo

/** How big a cached copy is, its JPEG quality, and how much of the cache its kind may take. */
enum class ImageSize(val folder: String, val quality: Int, val maxBytes: Long) {
    /** Small square-ish tile for lists and grids: the shorter side is at least 384 px, JPEG quality 75. */
    Thumb("thumbs", 75, 300L * MB),

    /** The big cards of "Pulizia rapida": sharp on a phone screen (longer side 1600 px) without the full original. */
    Card("cards", 80, 100L * MB),

    /** Only when a photo is opened full screen: the same pixels as the original, recompressed as JPEG quality 90. */
    Preview("full", 90, 200L * MB),
}

private const val MB = 1024L * 1024

/** Coil model for a cached copy of an image on the NAS. Size and date are part of it, so an edited photo refreshes. */
data class Thumb(
    val share: String,
    val path: String,
    val size: Long,
    val modified: Long,
    val variant: ImageSize = ImageSize.Thumb,
) {
    constructor(entry: RemoteEntry, variant: ImageSize = ImageSize.Thumb) :
        this(entry.share, entry.path, entry.size, entry.modified, variant)
}

/**
 * Makes each reduced copy once: the photo is read over SMB, scaled down, turned upright and saved as a small JPEG in
 * the cache. Every later view loads it from the phone without touching the NAS; the original stays on the NAS.
 */
class ThumbnailCache(private val dir: File, private val repository: NasRepository, val ratios: AspectRatios) {

    // a few at a time, so the NAS serves the visible ones quickly; previews get their own lane and never queue
    // behind a grid full of thumbnails
    private val thumbPermits = Semaphore(3)
    private val previewPermits = Semaphore(2)

    suspend fun get(thumb: Thumb): File {
        val target = file(thumb, thumb.variant)
        if (target.exists()) {
            target.setLastModified(System.currentTimeMillis())
            learnRatio(thumb, target)
            return target
        }
        val permits = if (thumb.variant == ImageSize.Thumb) thumbPermits else previewPermits
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                if (!target.exists()) {
                    create(thumb)
                    // the cache keeps itself small while photos are browsed, not only at the next start
                    if (created.incrementAndGet() % TRIM_EVERY == 0) runCatching { trim() }
                }
                learnRatio(thumb, target)
                target
            }
        }
    }

    /** Width / height of the photo as shown, once one of its thumbnails has been loaded; null before. */
    fun ratio(entry: RemoteEntry): Float? = ratios[key(Thumb(entry))]

    /** The thumbnail is upright already: its own size gives the shape, read from the file on the phone. */
    private fun learnRatio(thumb: Thumb, file: File) {
        if (thumb.variant != ImageSize.Thumb) return
        val key = key(thumb)
        if (ratios[key] != null) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) ratios.put(key, bounds.outWidth.toFloat() / bounds.outHeight)
    }

    private val created = AtomicInteger()

    /**
     * Keeps each kind of copy under its own limit, dropping the least recently viewed first: thumbnails come back
     * quickly from the NAS, full-resolution copies are kept only for the photos opened last.
     */
    @Synchronized
    fun trim() {
        // the reduced 1600 px previews of older versions are no longer used
        File(dir, "previews").deleteRecursively()
        for (variant in ImageSize.entries) {
            val files = File(dir, variant.folder).listFiles()?.sortedByDescending { it.lastModified() } ?: continue
            var total = 0L
            for (f in files) {
                total += f.length()
                if (total > variant.maxBytes) f.delete()
            }
        }
    }

    private fun file(thumb: Thumb, variant: ImageSize): File = File(File(dir, variant.folder), key(thumb) + ".jpg")

    /** Downloads the original once and writes the requested copy; a preview also leaves its thumbnail behind. */
    private fun create(thumb: Thumb) {
        val variants = if (thumb.variant == ImageSize.Thumb) listOf(ImageSize.Thumb) else listOf(thumb.variant, ImageSize.Thumb)
        val missing = variants.filter { !file(thumb, it).exists() }
        if (missing.isEmpty()) return
        dir.mkdirs()
        if (RemotePath.extension(RemotePath.name(thumb.path)) in VIDEO_EXTENSIONS) return createFromVideo(thumb, missing)
        val source = File.createTempFile("src", null, dir)
        try {
            repository.fs().openRead(thumb.share, thumb.path).use { input ->
                source.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Immagine non leggibile")
            val exif = runCatching { ExifInterface(source.path) }.getOrNull()
            for (variant in missing) {
                val target = file(thumb, variant)
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".part")
                write(source, bounds.outWidth, bounds.outHeight, variant, exif, temp)
                // a JPEG already lighter than our copy stays as it is: same pixels, fewer bytes, nothing lost
                if (variant == ImageSize.Preview && bounds.outMimeType == "image/jpeg" && source.length() <= temp.length()) {
                    source.copyTo(temp, overwrite = true)
                }
                temp.renameTo(target)
            }
        } finally {
            source.delete()
        }
    }

    /** A frame near the start of the video, read straight from the NAS: only the parts the decoder asks for travel. */
    private fun createFromVideo(thumb: Thumb, missing: List<ImageSize>) {
        val frame = repository.fs().openRandom(thumb.share, thumb.path).use { reader ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ReaderDataSource(reader))
                retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
            } finally {
                retriever.release()
            }
        } ?: throw IllegalStateException("Video non leggibile")
        try {
            for (variant in missing) {
                val scale = scaleFor(variant, frame.width, frame.height)
                val w = (frame.width * scale).roundToInt().coerceAtLeast(1)
                val h = (frame.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(frame, w, h, true) else frame
                val target = file(thumb, variant)
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".part")
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, variant.quality, it) }
                temp.renameTo(target)
                if (bitmap !== frame) bitmap.recycle()
            }
        } finally {
            frame.recycle()
        }
    }

    private fun write(source: File, width: Int, height: Int, variant: ImageSize, exif: ExifInterface?, target: File) {
        // a very large photo may not fit in memory at full size: then it is halved until it does
        var reduce = 1
        while (true) {
            try {
                val bitmap = decode(source, width, height, variant, reduce)
                val upright = rotate(bitmap, exif)
                try {
                    target.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, variant.quality, it) }
                } finally {
                    if (upright !== bitmap) upright.recycle()
                    bitmap.recycle()
                }
                return
            } catch (e: OutOfMemoryError) {
                if (reduce >= 8) throw IllegalStateException("Immagine troppo grande")
                reduce *= 2
            }
        }
    }

    private fun decode(source: File, width: Int, height: Int, variant: ImageSize, reduce: Int = 1): Bitmap {
        // sample down by powers of two while the result stays at least as big as needed, then scale exactly
        val scale = scaleFor(variant, width, height) / reduce
        var sample = 1
        while (width / (sample * 2) >= width * scale && height / (sample * 2) >= height * scale) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Immagine non leggibile")
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        if (decoded.width <= w || decoded.height <= h) return decoded
        return Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
    }

    private fun scaleFor(variant: ImageSize, width: Int, height: Int): Float = when (variant) {
        ImageSize.Thumb -> min(1f, THUMB_PX.toFloat() / min(width, height))
        ImageSize.Card -> min(1f, CARD_PX.toFloat() / max(width, height))
        ImageSize.Preview -> 1f
    }

    private fun rotate(bitmap: Bitmap, exif: ExifInterface?): Bitmap {
        val matrix = Matrix()
        when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun key(thumb: Thumb): String =
        MessageDigest.getInstance("SHA-1")
            .digest("${thumb.share}/${thumb.path}:${thumb.size}:${thumb.modified}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    class Fetcher(private val thumb: Thumb, private val cache: ThumbnailCache) : coil3.fetch.Fetcher {
        override suspend fun fetch(): FetchResult {
            val file = cache.get(thumb)
            return SourceFetchResult(
                source = ImageSource(file = file.toOkioPath(), fileSystem = FileSystem.SYSTEM),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }

        class Factory(private val cache: ThumbnailCache) : coil3.fetch.Fetcher.Factory<Thumb> {
            override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): coil3.fetch.Fetcher =
                Fetcher(data, cache)
        }
    }

    companion object {
        const val THUMB_PX = 384
        const val CARD_PX = 1600
        /** New copies made between two trims of the cache. */
        private const val TRIM_EVERY = 25
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
    }
}
