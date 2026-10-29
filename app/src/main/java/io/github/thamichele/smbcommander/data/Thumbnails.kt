package io.github.thamichele.smbcommander.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
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

/** Coil model for the thumbnail of an image on the NAS. Size and date are part of it, so an edited photo refreshes. */
data class Thumb(val share: String, val path: String, val size: Long, val modified: Long) {
    constructor(entry: RemoteEntry) : this(entry.share, entry.path, entry.size, entry.modified)
}

/**
 * Makes each thumbnail once: the photo is read over SMB, scaled down to [TARGET_PX], turned upright and saved as a
 * small JPEG in the cache. Every later view of the folder loads it from the phone, without touching the NAS.
 */
class ThumbnailCache(private val dir: File, private val repository: NasRepository) {

    // a few at a time: the NAS serves the visible ones quickly instead of everything at once
    private val permits = Semaphore(3)

    suspend fun get(thumb: Thumb): File {
        val target = File(dir, key(thumb) + ".jpg")
        if (target.exists()) return target
        return permits.withPermit {
            withContext(Dispatchers.IO) {
                if (!target.exists()) create(thumb, target)
                target
            }
        }
    }

    private fun create(thumb: Thumb, target: File) {
        dir.mkdirs()
        val source = File.createTempFile("src", null, dir)
        try {
            repository.fs().openRead(thumb.share, thumb.path).use { input ->
                source.outputStream().use { input.copyTo(it, 1 shl 16) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_PX && bounds.outHeight / (sample * 2) >= TARGET_PX) sample *= 2
            val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw IllegalStateException("Immagine non leggibile")
            val upright = rotate(bitmap, runCatching { ExifInterface(source.path) }.getOrNull())
            val temp = File(dir, target.name + ".part")
            temp.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            temp.renameTo(target)
        } finally {
            source.delete()
        }
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
        const val TARGET_PX = 384
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
    }
}
