package io.github.thamichele.smbcommander.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.Disposable
import coil3.request.ImageRequest
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.Thumb
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.max
import kotlin.math.min

/**
 * Loads the thumbnails just past the edge of the screen, in the direction the gallery is scrolling, so they are
 * ready (on the phone and decoded) before they come into view and scrolling does not stutter.
 *
 * [photos] is the whole gallery in order; [photoAt] gives, for an item of the lazy list or grid, the index in
 * [photos] of its first photo (a month header gives the first photo of its month).
 */
@Composable
fun PrefetchThumbnails(position: ScrollPosition, photos: List<RemoteEntry>, ahead: Int, photoAt: (Int) -> Int) {
    val context = LocalPlatformContext.current
    val currentPhotoAt by rememberUpdatedState(photoAt)
    LaunchedEffect(position, photos, ahead) {
        if (photos.isEmpty()) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        // what is being loaded ahead, by photo index: kept across small moves, so each photo is asked for once
        val pending = HashMap<Int, Disposable>()
        var lastFirst = 0
        try {
            snapshotFlow { position.first.toInt() to position.last }
                .distinctUntilChanged()
                .collectLatest { (first, last) ->
                    // wait for the scroll to settle a moment: a fling would otherwise queue a page at every frame
                    delay(60)
                    val forward = first >= lastFirst
                    lastFirst = first
                    val from = currentPhotoAt(first).coerceIn(0, photos.size - 1)
                    val to = currentPhotoAt(last).coerceIn(0, photos.size - 1)
                    // nearest first; the ones behind get a smaller share, for a change of direction
                    val next = if (forward) {
                        ((to + 1)..min(photos.size - 1, to + ahead)) + (max(0, from - ahead / 3) until from).reversed()
                    } else {
                        (max(0, from - ahead) until from).reversed() + ((to + 1)..min(photos.size - 1, to + ahead / 3))
                    }
                    // what a fast scroll left behind is no longer needed soon: it stops waiting for the NAS
                    val wanted = next.toHashSet()
                    val iterator = pending.entries.iterator()
                    while (iterator.hasNext()) {
                        val entry = iterator.next()
                        if (entry.key !in wanted) {
                            entry.value.dispose()
                            iterator.remove()
                        }
                    }
                    for (i in next) {
                        if (i !in pending) pending[i] = loader.enqueue(ImageRequest.Builder(context).data(Thumb(photos[i])).build())
                    }
                }
        } finally {
            pending.values.forEach { it.dispose() }
        }
    }
}

/** For a list or grid made of month headers each followed by its photos: the photo index of each item. */
fun photoIndexIn(starts: IntArray, offsets: List<Int>): (Int) -> Int = { index ->
    val m = monthAt(starts, index)
    offsets[m] + max(0, index - starts[m] - 1)
}
