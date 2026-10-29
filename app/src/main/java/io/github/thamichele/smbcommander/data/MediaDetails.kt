package io.github.thamichele.smbcommander.data

import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import io.github.thamichele.smbcommander.core.NasFileSystem
import io.github.thamichele.smbcommander.core.RemoteEntry
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * What a photo or video says about itself, read from the NAS: for photos only the first part of the file (the EXIF
 * block), for videos only the parts the metadata reader asks for. Each line is a label and its value.
 */
object MediaDetails {

    fun read(fs: NasFileSystem, entry: RemoteEntry): List<Pair<String, String>> = when {
        entry.isImage -> photo(fs, entry)
        entry.isVideo -> video(fs, entry)
        else -> emptyList()
    }

    private fun photo(fs: NasFileSystem, entry: RemoteEntry): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { fs.openRead(entry.share, entry.path).use { BitmapFactory.decodeStream(it, null, bounds) } }
        val exif = runCatching { fs.openRead(entry.share, entry.path).use { ExifInterface(it) } }.getOrNull()
        val rotated = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) in setOf(5, 6, 7, 8)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            val (w, h) = if (rotated) bounds.outHeight to bounds.outWidth else bounds.outWidth to bounds.outHeight
            rows += "Risoluzione" to "$w × $h px (${"%.1f".format(Locale.ITALY, w.toLong() * h / 1_000_000.0)} MP)"
        }
        if (exif == null) return rows
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)?.let { exifDate(it) }?.let { rows += "Scattata il" to it }
        listOfNotNull(exif.getAttribute(ExifInterface.TAG_MAKE)?.trim(), exif.getAttribute(ExifInterface.TAG_MODEL)?.trim())
            .filter { it.isNotEmpty() }
            .let { parts ->
                // "Google Pixel 8" rather than "Google Google Pixel 8"
                val model = parts.lastOrNull()
                val camera = if (parts.size == 2 && model != null && model.startsWith(parts[0], ignoreCase = true)) model else parts.joinToString(" ")
                if (camera.isNotEmpty()) rows += "Fotocamera" to camera
            }
        exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() }?.let { rows += "Obiettivo" to it }
        val shot = listOfNotNull(
            exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "f/" + "%.1f".format(Locale.ITALY, it) },
            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let(::exposure),
            exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 }?.let { "ISO $it" },
            exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { "${trim(it)} mm" },
        )
        if (shot.isNotEmpty()) rows += "Scatto" to shot.joinToString(" · ")
        val latLong = FloatArray(2)
        @Suppress("DEPRECATION")
        if (exif.getLatLong(latLong)) rows += "Posizione" to coordinates(latLong[0].toDouble(), latLong[1].toDouble())
        return rows
    }

    private fun video(fs: NasFileSystem, entry: RemoteEntry): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        runCatching {
            fs.openRandom(entry.share, entry.path).use { reader ->
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(ReaderDataSource(reader))
                    val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                    val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                    val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (w != null && h != null) {
                        rows += "Risoluzione" to if (rotation % 180 != 0) "$h × $w px" else "$w × $h px"
                    }
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { rows += "Durata" to duration(it) }
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.let(::videoDate)?.let { rows += "Registrato il" to it }
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 }?.let {
                        rows += "Bitrate" to "${(it / 1000.0).roundToInt()} kbit/s"
                    }
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let(::iso6709)?.let { rows += "Posizione" to it }
                } finally {
                    r.release()
                }
            }
        }
        return rows
    }

    private fun exifDate(value: String): String? = runCatching {
        val parsed = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(value.trim()) ?: return null
        SimpleDateFormat("d MMMM yyyy, HH:mm:ss", Locale.ITALY).format(parsed)
    }.getOrNull()

    /** "20261029T101500.000Z" */
    private fun videoDate(value: String): String? = runCatching {
        val parser = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val parsed = parser.parse(value.take(15)) ?: return null
        if (parsed.time <= 0) return null
        SimpleDateFormat("d MMMM yyyy, HH:mm:ss", Locale.ITALY).format(parsed)
    }.getOrNull()

    /** "+45.4642+009.1900/" */
    private fun iso6709(value: String): String? {
        val m = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(value) ?: return null
        return coordinates(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
    }

    fun coordinates(lat: Double, lon: Double) = "%.6f, %.6f".format(Locale.US, lat, lon)

    private fun exposure(seconds: Double): String =
        if (seconds >= 1) "${trim(seconds)} s" else "1/${(1 / seconds).roundToInt()} s"

    private fun trim(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(Locale.ITALY, v)

    private fun duration(ms: Long): String {
        val total = (ms + 500) / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
