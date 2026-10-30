package io.github.thamichele.smbcommander.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import io.github.thamichele.smbcommander.core.RemotePath

/**
 * Name and size of a document picked or shared from another app, as its provider reports them (-1 when the size is
 * unknown). The name comes from that app, so it must be a plain file name: anything else becomes "file".
 */
fun ContentResolver.describe(uri: Uri): Pair<String, Long> {
    runCatching {
        query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val name = c.getString(0)?.takeIf { RemotePath.isValidName(it) } ?: "file"
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                return name to size
            }
        }
    }
    return (uri.lastPathSegment?.substringAfterLast('/')?.takeIf { RemotePath.isValidName(it) } ?: "file") to -1L
}
