package io.github.thamichele.smbcommander.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import io.github.thamichele.smbcommander.core.RemoteEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val RemoteEntry.isImage: Boolean get() = !isDirectory && extension in ThumbnailCache.IMAGE_EXTENSIONS

/** How big a cached copy of a NAS image is. */
enum class ImageSize(val folder: String) {
    /** Small square-ish tile for lists and grids: the shorter side is at least 384 px. */
    Thumb("thumbs"),

    /** Full-screen preview: the longer side is at most 1600 px, JPEG quality 72. Light, but clearly readable. */
    Preview("previews"),
}

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
class ThumbnailCache(private val dir: File, private val repository: NasRepository) {

    // a few at a time, so the NAS serves the visible ones quickly; previews get their own lane and never queue
    // behind a grid full of thumbnails
    private val thumbPermits = Semaphore(3)
    private val previewPermits = Semaphore(2)

    suspend fun get(thumb: Thumb): File {
        val target = file(thumb, thumb.variant)
        if (target.exists()) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val permits = if (thumb.variant == ImageSize.Preview) previewPermits else thumbPermits
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                if (!target.exists()) create(thumb)
                target
            }
        }
    }

    /** Keeps the previews under [maxBytes], dropping the least recently viewed first. */
    fun trimPreviews(maxBytes: Long = 400L * 1024 * 1024) {
        val files = File(dir, ImageSize.Preview.folder).listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > maxBytes) f.delete()
        }
    }

    private fun file(thumb: Thumb, variant: ImageSize): File = File(File(dir, variant.folder), key(thumb) + ".jpg")

    /** Downloads the original once and writes the requested copy; a preview also leaves its thumbnail behind. */
    private fun create(thumb: Thumb) {
        val variants = if (thumb.variant == ImageSize.Preview) listOf(ImageSize.Preview, ImageSize.Thumb) else listOf(ImageSize.Thumb)
        val missing = variants.filter { !file(thumb, it).exists() }
        if (missing.isEmpty()) return
        dir.mkdirs()
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
                val bitmap = decode(source, bounds.outWidth, bounds.outHeight, variant)
                val upright = rotate(bitmap, exif)
                val target = file(thumb, variant)
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, target.name + ".part")
                temp.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, if (variant == ImageSize.Preview) 72 else 85, it) }
                temp.renameTo(target)
                if (upright !== bitmap) upright.recycle()
                bitmap.recycle()
            }
        } finally {
            source.delete()
        }
    }

    private fun decode(source: File, width: Int, height: Int, variant: ImageSize): Bitmap {
        // sample down by powers of two while the result stays at least as big as needed, then scale exactly
        val scale = when (variant) {
            ImageSize.Thumb -> min(1f, THUMB_PX.toFloat() / min(width, height))
            ImageSize.Preview -> min(1f, PREVIEW_PX.toFloat() / max(width, height))
        }
        var sample = 1
        while (width / (sample * 2) >= width * scale && height / (sample * 2) >= height * scale) sample *= 2
        val decoded = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("Immagine non leggibile")
        val w = (width * scale).roundToInt().coerceAtLeast(1)
        val h = (height * scale).roundToInt().coerceAtLeast(1)
        if (decoded.width <= w || decoded.height <= h) return decoded
        return Bitmap.createScaledBitmap(decoded, w, h, true).also { if (it !== decoded) decoded.recycle() }
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
        const val PREVIEW_PX = 1600
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    }
}
