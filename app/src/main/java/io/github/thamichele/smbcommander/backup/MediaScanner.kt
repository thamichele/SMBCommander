package io.github.thamichele.smbcommander.backup

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class Album(val id: String, val name: String, val count: Int)

data class MediaItem(
    val uri: Uri,
    val key: String,
    val name: String,
    val size: Long,
    /** When it was taken, epoch ms; falls back to the file date. */
    val taken: Long,
    val albumId: String,
)

/** Reads the phone's gallery through MediaStore. */
class MediaScanner(private val context: Context) {

    fun albums(includeVideos: Boolean): List<Album> {
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (collection in collections(includeVideos)) {
            query(collection) { id, name, _, _, _, _ ->
                val previous = counts[id]
                counts[id] = (previous?.first ?: name) to ((previous?.second ?: 0) + 1)
            }
        }
        return counts.map { (id, v) -> Album(id, v.first, v.second) }
            .sortedWith(compareByDescending<Album> { it.name.equals("Camera", true) }.thenByDescending { it.count })
    }

    /** The camera album, the usual default for a backup. */
    fun cameraAlbum(albums: List<Album>): Album? =
        albums.firstOrNull { it.name.equals("Camera", ignoreCase = true) || it.name.equals("Fotocamera", ignoreCase = true) }

    fun items(albums: Set<String>, includeVideos: Boolean): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        for (collection in collections(includeVideos)) {
            val kind = if (collection == MediaStore.Video.Media.EXTERNAL_CONTENT_URI) "v" else "i"
            query(collection) { bucket, _, id, name, size, taken ->
                if (bucket in albums && size > 0) {
                    result += MediaItem(
                        uri = ContentUris.withAppendedId(collection, id),
                        key = "$kind:$id:$size",
                        name = name,
                        size = size,
                        taken = taken,
                        albumId = bucket,
                    )
                }
            }
        }
        return result.sortedBy { it.taken }
    }

    private fun collections(includeVideos: Boolean): List<Uri> =
        if (includeVideos) {
            listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        } else {
            listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }

    private inline fun query(
        collection: Uri,
        row: (bucketId: String, bucketName: String, id: Long, name: String, size: Long, taken: Long) -> Unit,
    ) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.BUCKET_ID,
            MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        )
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val modified = c.getLong(4) * 1000
                row(
                    c.getString(5) ?: "",
                    c.getString(6) ?: "Senza nome",
                    c.getLong(0),
                    c.getString(1) ?: "media_${c.getLong(0)}",
                    c.getLong(2),
                    c.getLong(3).takeIf { it > 0 } ?: modified,
                )
            }
        }
    }
}
