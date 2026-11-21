/*
 * Thumbnails.kt - the reduced copies of the NAS photos kept on the phone, and the Coil fetcher that loads them
 *
 * Each copy is made once: the photo is read over SMB, scaled down, turned upright and saved as a JPEG in the cache;
 * every later view loads it from the phone. Video frames, song covers and the first page of a PDF become pictures the
 * same way. The originals stay on the NAS and are never changed. Animated video previews are VideoFrames' job.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val RemoteEntry.isImage: Boolean get() = !isDirectory && extension in ThumbnailCache.IMAGE_EXTENSIONS
val RemoteEntry.isVideo: Boolean get() = !isDirectory && extension in ThumbnailCache.VIDEO_EXTENSIONS

/** Photos and videos: what the gallery and the viewer show. */
val RemoteEntry.isMedia: Boolean get() = isImage || isVideo

val RemoteEntry.isAudio: Boolean get() = !isDirectory && extension in ThumbnailCache.AUDIO_EXTENSIONS
private val RemoteEntry.isPdf: Boolean get() = !isDirectory && extension == "pdf"

/** Files with a picture of their own in lists: photos, videos, the first page of a PDF, the cover of a song. */
val RemoteEntry.hasPicture: Boolean get() = isMedia || isAudio || (isPdf && size <= ThumbnailCache.MAX_PDF_BYTES)

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
    private val thumbPermits = Semaphore(THUMBS_AT_ONCE)
    private val previewPermits = Semaphore(PREVIEWS_AT_ONCE)
    /** The background fill of [warmUp]: two downloads at most, besides those of the screen. */
    private val warmPermits = Semaphore(WARM_UPS_AT_ONCE)

    /** One lock per copy being made: a photo asked twice at once (ahead of the screen, then on it) is read once. */
    private val making = ConcurrentHashMap<String, Mutex>()

    /** Files found without a picture during this run. */
    private val noPicture: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * get() - the copy of a photo, made now when it is not on the phone yet
     * @param thumb the photo and the size wanted
     *
     * Context: suspends while the photo is read from the NAS; cancelling stops the download.
     * Return: the JPEG file in the cache.
     */
    suspend fun get(thumb: Thumb): File {
        val name = key(thumb)
        val target = file(name, thumb.variant)
        // songs without a cover and unreadable PDFs are asked once per run, not at every scroll
        if (name in noPicture) throw IllegalStateException("Nessuna immagine")
        if (target.exists()) {
            target.setLastModified(System.currentTimeMillis())
            learnRatio(thumb, name, target)
            return target
        }
        make(thumb, target, if (thumb.variant == ImageSize.Thumb) thumbPermits else previewPermits)
        learnRatio(thumb, name, target)
        return target
    }

    /** Makes the copy at [target] if it is still missing, one maker per copy, within [permits] when given. */
    private suspend fun make(thumb: Thumb, target: File, permits: Semaphore?) {
        val lock = making.getOrPut(target.path) { Mutex() }
        try {
            lock.withLock {
                (permits ?: NO_LIMIT).withPermit {
                    withContext(Dispatchers.IO) {
                        if (!target.exists()) {
                            val job = currentCoroutineContext()[Job]
                            // a photo scrolled past (or dragged past with the date bar) stops downloading at once
                            val wanted = { job?.isActive != false }
                            // thumbnails are made at low priority: the CPU goes first to the screen, which keeps
                            // scrolling smoothly while dozens of them are being made
                            if (thumb.variant == ImageSize.Thumb) atBackgroundPriority { create(thumb, wanted) } else create(thumb, wanted)
                            // the cache keeps itself small while photos are browsed, not only at the next start
                            if (created.incrementAndGet() % TRIM_EVERY == 0) runCatching { trim() }
                        }
                    }
                }
            }
        } finally {
            // nobody else waiting: the lock is not needed any more
            if (!lock.isLocked) making.remove(target.path, lock)
        }
    }

    /**
     * warmUp() - fills the thumbnail cache in the background, newest photos first
     * @param photos the photos, in the order they should be made
     *
     * Independent of what is on screen: when the gallery scrolls or jumps somewhere, its thumbnails are already on
     * the phone. It stops before the thumbnail limit of the cache, so it never pushes out what it has just made, and
     * it works in its own lane with fewer downloads at once, so what is on screen always comes first.
     *
     * Context: suspends until done or cancelled.
     * Return: nothing.
     */
    suspend fun warmUp(photos: List<RemoteEntry>) = withContext(Dispatchers.IO) {
        val budget = ImageSize.Thumb.maxBytes * WARM_UP_SHARE_PERCENT / PERCENT
        val used = AtomicLong()
        val learnt = AtomicInteger()
        try {
            coroutineScope {
                for (photo in photos) {
                    if (used.get() > budget) break
                    val thumb = Thumb(photo)
                    val name = key(thumb)
                    val target = file(name, ImageSize.Thumb)
                    if (target.exists()) {
                        used.addAndGet(target.length())
                        continue
                    }
                    warmPermits.acquire()
                    launch {
                        try {
                            runCatching { make(thumb, target, permits = null) }
                            if (target.exists()) {
                                used.addAndGet(target.length())
                                // the shapes reach the mosaic in batches, not one rearrangement per photo
                                val isNew = learnRatio(thumb, name, target, publish = false)
                                if (isNew && learnt.incrementAndGet() % RATIO_BATCH == 0) ratios.publish()
                            }
                        } finally {
                            warmPermits.release()
                        }
                    }
                }
            }
        } finally {
            if (learnt.get() % RATIO_BATCH != 0) ratios.publish()
        }
    }

    /**
     * background() - the thumbnail of a photo for work done in the background (sorting the quick cleanup)
     * @param photo the photo
     *
     * From the phone when it is there, else made in the lane of [warmUp], so it never slows down what is on screen.
     *
     * Context: suspends while the photo is read from the NAS.
     * Return: the JPEG file in the cache.
     */
    suspend fun background(photo: RemoteEntry): File {
        val thumb = Thumb(photo)
        val name = key(thumb)
        val target = file(name, ImageSize.Thumb)
        if (!target.exists()) make(thumb, target, warmPermits)
        if (!target.exists()) throw IllegalStateException("Miniatura non disponibile")
        // the mosaic hears of new shapes with the next batch of [warmUp], not one rearrangement per photo
        learnRatio(thumb, name, target, publish = false)
        return target
    }

    /**
     * ratio() - width / height of a photo as shown
     * @param entry the photo
     *
     * Return: the shape, once one of its thumbnails has been loaded; null before.
     */
    fun ratio(entry: RemoteEntry): Float? = ratios[key(Thumb(entry))]

    /* The thumbnail is upright already: its own size gives the shape, read from the file on the phone. */
    private fun learnRatio(thumb: Thumb, key: String, file: File, publish: Boolean = true): Boolean {
        if (thumb.variant != ImageSize.Thumb) return false
        if (ratios[key] != null) return false
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        ratios.put(key, bounds.outWidth.toFloat() / bounds.outHeight, publish)
        return true
    }

    private val created = AtomicInteger()

    /**
     * trim() - keeps each kind of copy under its own limit, dropping the least recently viewed first
     *
     * Thumbnails come back quickly from the NAS, full-resolution copies are kept only for the photos opened last.
     *
     * Context: blocking, walks the cache folders.
     * Return: nothing.
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

    private fun file(key: String, variant: ImageSize): File = File(File(dir, variant.folder), "$key.jpg")

    /** Downloads the original once and writes the requested copy; a preview also leaves its thumbnail behind. */
    private fun create(thumb: Thumb, wanted: () -> Boolean) {
        val variants = if (thumb.variant == ImageSize.Thumb) listOf(ImageSize.Thumb) else listOf(thumb.variant, ImageSize.Thumb)
        val name = key(thumb)
        val missing = variants.filter { !file(name, it).exists() }
        if (missing.isEmpty()) return
        dir.mkdirs()
        val ext = RemotePath.extension(RemotePath.name(thumb.path)).lowercase()
        if (ext in VIDEO_EXTENSIONS) return createFromVideo(thumb, missing)
        if (ext in AUDIO_EXTENSIONS || ext == "pdf") {
            val picture = try {
                if (ext == "pdf") pdfPage(thumb) else audioCover(thumb)
            } catch (e: Exception) {
                null
            }
            if (picture == null) {
                noPicture += name
                throw IllegalStateException("Nessuna immagine")
            }
            return writeBitmap(thumb, missing, picture)
        }
        val source = File.createTempFile("src", null, dir)
        try {
            download(thumb, source, wanted)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IllegalStateException("Immagine non leggibile")
            val exif = runCatching { ExifInterface(source.path) }.getOrNull()
            for (variant in missing) {
                val target = file(name, variant)
                val temp = tempFor(target)
                write(source, bounds.outWidth, bounds.outHeight, variant, exif, temp)
                // a JPEG already lighter than our copy stays as it is: same pixels, fewer bytes, nothing lost
                if (variant == ImageSize.Preview && bounds.outMimeType == "image/jpeg" && source.length() <= temp.length()) {
                    source.copyTo(temp, overwrite = true)
                }
                if (!temp.renameTo(target)) temp.delete()
            }
        } finally {
            source.delete()
        }
    }

    /* Copies the original to [target] on the phone, stopping as soon as nobody wants it any more. */
    private fun download(thumb: Thumb, target: File, wanted: () -> Boolean) {
        repository.fs().openRead(thumb.share, thumb.path).use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                while (true) {
                    if (!wanted()) throw CancellationException("Non serve più")
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    /* A name of its own next to [target]: the same photo may be asked twice at once (on screen and ahead of it). */
    private fun tempFor(target: File): File {
        target.parentFile?.mkdirs()
        return File.createTempFile(target.name, PART_SUFFIX, target.parentFile)
    }

    /** The cover inside a song (ID3, FLAC, MP4 tags), read straight from the NAS: only the tags travel. */
    private fun audioCover(thumb: Thumb): Bitmap? {
        val bytes = repository.fs().openRandom(thumb.share, thumb.path).use { reader ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ReaderDataSource(reader))
                retriever.embeddedPicture
            } finally {
                retriever.release()
            }
        } ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= THUMB_PX && bounds.outHeight / (sample * 2) >= THUMB_PX) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** The first page of a PDF, drawn on white by Android's own renderer; the file is fetched to the cache first. */
    private fun pdfPage(thumb: Thumb): Bitmap? {
        if (thumb.size > MAX_PDF_BYTES) return null
        val source = File.createTempFile("pdf", null, dir)
        try {
            source.outputStream().use { out -> repository.fs().download(thumb.share, thumb.path, out) }
            ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (renderer.pageCount == 0) return null
                    renderer.openPage(0).use { page ->
                        val scale = CARD_PX.toFloat() / max(page.width, page.height)
                        val w = (page.width * scale).roundToInt().coerceAtLeast(1)
                        val h = (page.height * scale).roundToInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        return bitmap
                    }
                }
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
                retriever.getFrameAtTime(VIDEO_FRAME_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.frameAtTime
            } finally {
                retriever.release()
            }
        } ?: throw IllegalStateException("Video non leggibile")
        writeBitmap(thumb, missing, frame)
    }

    /** Saves [frame] as each copy in [missing], scaled as each one wants, then frees it. */
    private fun writeBitmap(thumb: Thumb, missing: List<ImageSize>, frame: Bitmap) {
        try {
            val name = key(thumb)
            for (variant in missing) {
                val scale = scaleFor(variant, frame.width, frame.height)
                val w = (frame.width * scale).roundToInt().coerceAtLeast(1)
                val h = (frame.height * scale).roundToInt().coerceAtLeast(1)
                val bitmap = if (scale < 1f) Bitmap.createScaledBitmap(frame, w, h, true) else frame
                val target = file(name, variant)
                val temp = tempFor(target)
                temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, variant.quality, it) }
                if (!temp.renameTo(target)) temp.delete()
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
                if (reduce >= MAX_REDUCE) throw IllegalStateException("Immagine troppo grande")
                reduce *= 2
            }
        }
    }

    private fun decode(source: File, width: Int, height: Int, variant: ImageSize, reduce: Int = 1): Bitmap {
        // sample down by powers of two while the result stays at least as big as needed, then scale exactly
        val scale = scaleFor(variant, width, height) / reduce
        var sample = 1
        while (width / (sample * 2) >= width * scale && height / (sample * 2) >= height * scale) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
            inSampleSize = sample
            // half the memory for the small copies (JPEGs have no transparency anyway): less garbage to collect while
            // the gallery scrolls
            if (variant != ImageSize.Preview) inPreferredConfig = Bitmap.Config.RGB_565
        })
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

    /**
     * key() - the name of the copies of a photo in the cache
     * @param thumb the photo
     *
     * Return: a hash of where the photo is and of its size and date, so an edited photo gets new copies.
     */
    fun key(thumb: Thumb): String = cacheKey("${thumb.share}/${thumb.path}:${thumb.size}:${thumb.modified}")

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
        /** For work already limited elsewhere. */
        private val NO_LIMIT = Semaphore(Int.MAX_VALUE)
        /** New copies made between two trims of the cache. */
        private const val TRIM_EVERY = 25
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "3gp", "mkv", "webm")
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma")
        /** Bigger PDFs get no picture: the whole file would have to come to the phone for it. */
        const val MAX_PDF_BYTES = 30L * 1024 * 1024

        private const val THUMBS_AT_ONCE = 4
        private const val PREVIEWS_AT_ONCE = 2
        private const val WARM_UPS_AT_ONCE = 2

        /* The warm-up stops at 80% of the thumbnail limit, so it never pushes out what it has just made. */
        private const val WARM_UP_SHARE_PERCENT = 80
        private const val PERCENT = 100

        /* Shapes learnt by the warm-up reach the mosaic in batches of this many. */
        private const val RATIO_BATCH = 100
        private const val DOWNLOAD_BUFFER_BYTES = 1 shl 16

        /* The frame of a video thumbnail: one second in, past the black of the very first frames. */
        private const val VIDEO_FRAME_US = 1_000_000L

        /* A photo too big for memory is halved up to three times before giving up. */
        private const val MAX_REDUCE = 8
        private const val PART_SUFFIX = ".part"
    }
}
