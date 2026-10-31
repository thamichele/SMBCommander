package io.github.thamichele.smbcommander.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.theme.Arancio
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

/** What the date scroller needs of a lazy list or grid. */
interface ScrollPosition {
    val total: Int
    val visible: Int
    /** Index of the first visible item, plus how much of it is already scrolled away (0..1). */
    val first: Float
    val scrolling: Boolean
    /** Index of the last item at least partly on screen. */
    val last: Int
    suspend fun jumpTo(index: Int)
}

@Composable
fun rememberScrollPosition(state: LazyListState): ScrollPosition = remember(state) {
    object : ScrollPosition {
        override val total get() = state.layoutInfo.totalItemsCount
        override val visible get() = state.layoutInfo.visibleItemsInfo.size
        override val first: Float
            get() {
                val item = state.layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0f
                return item.index + if (item.size > 0) (state.firstVisibleItemScrollOffset.toFloat() / item.size).coerceIn(0f, 1f) else 0f
            }
        override val scrolling get() = state.isScrollInProgress
        override val last get() = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        override suspend fun jumpTo(index: Int) = state.scrollToItem(index)
    }
}

@Composable
fun rememberScrollPosition(state: LazyGridState): ScrollPosition = remember(state) {
    object : ScrollPosition {
        override val total get() = state.layoutInfo.totalItemsCount
        override val visible get() = state.layoutInfo.visibleItemsInfo.size
        override val first: Float
            get() {
                val item = state.layoutInfo.visibleItemsInfo.firstOrNull() ?: return 0f
                val height = item.size.height
                return item.index + if (height > 0) (state.firstVisibleItemScrollOffset.toFloat() / height).coerceIn(0f, 1f) else 0f
            }
        override val scrolling get() = state.isScrollInProgress
        override val last get() = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        override suspend fun jumpTo(index: Int) = state.scrollToItem(index)
    }
}

/**
 * Fast scrolling by date along the right edge, as in the phone's own gallery: a handle appears while the photos
 * scroll; dragging it (or touching the edge while it shows) runs through the whole gallery, with the month and
 * year under the finger and the years marked along the way.
 *
 * [starts] holds, for each of [months], the index of its header among the items of the list or grid.
 */
@Composable
fun DateScroller(position: ScrollPosition, months: List<MonthGroup>, starts: IntArray, modifier: Modifier = Modifier) {
    val c = Arancio.colors
    val range by remember(position) { derivedStateOf { (position.total - position.visible).coerceAtLeast(0) } }
    // only worth it when the gallery is several screens long
    val useful by remember(position) { derivedStateOf { position.visible > 0 && position.total > position.visible * 3 } }
    if (!useful || months.isEmpty()) return

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableStateOf<Jump?>(null) }
    var shown by remember { mutableStateOf(false) }
    val scrolling by remember(position) { derivedStateOf { position.scrolling } }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) {
            shown = true
        } else {
            delay(1500)
            shown = false
        }
    }
    // the list follows the finger, skipping the positions it had no time for
    LaunchedEffect(position) {
        snapshotFlow { target }.collectLatest { if (it != null) position.jumpTo(it.index) }
    }

    val fraction by remember(position) {
        derivedStateOf { if (range == 0) 0f else (position.first / range).coerceIn(0f, 1f) }
    }
    val shownFraction = if (dragging) dragFraction else fraction

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
        val travel = (trackPx - thumbPx).coerceAtLeast(1f)
        val thumbY = shownFraction * travel
        // read by the gesture without restarting it: the visible count, and so the range, changes while scrolling
        val currentTravel by rememberUpdatedState(travel)
        val currentRange by rememberUpdatedState(range)
        val currentThumb by rememberUpdatedState(thumbPx)

        val index = if (dragging) (dragFraction * range).roundToInt() else position.first.toInt()
        val month = months[monthAt(starts, index)]

        // year marks along the edge while dragging, left out where they would overlap
        if (dragging) {
            val minGap = with(density) { 28.dp.toPx() }
            var lastY = -minGap
            var lastYear = -1
            months.forEachIndexed { m, group ->
                if (group.year == lastYear) return@forEachIndexed
                lastYear = group.year
                val y = (if (range == 0) 0f else (starts[m].toFloat() / range).coerceIn(0f, 1f)) * travel + thumbPx / 2
                if (y - lastY < minGap) return@forEachIndexed
                lastY = y
                Text(
                    group.year.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = c.textSecondary,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, (y - with(density) { 9.dp.toPx() }).roundToInt()) }
                        .padding(end = 40.dp)
                        .background(c.surface.copy(alpha = 0.9f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY.roundToInt()) },
        ) {
            Box(contentAlignment = Alignment.CenterEnd) {
                if (dragging) {
                    Text(
                        "${monthName(month.month)} ${month.year}",
                        style = MaterialTheme.typography.titleSmall,
                        color = c.text,
                        modifier = Modifier
                            .padding(end = 48.dp)
                            .background(c.surfaceRaised, RoundedCornerShape(999.dp))
                            .border(1.dp, c.border, RoundedCornerShape(999.dp))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .size(width = 28.dp, height = THUMB_HEIGHT)
                        .background(if (dragging) c.accent else c.surfaceRaised, RoundedCornerShape(999.dp))
                        .border(1.dp, if (dragging) c.accent else c.border, RoundedCornerShape(999.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.UpDown,
                        contentDescription = "Scorri per data",
                        tint = if (dragging) c.onAccent else c.text,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        // the touch strip exists only while the handle shows, so the photos at the edge stay tappable otherwise
        if (shown) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(36.dp)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        fun follow(y: Float) {
                            dragFraction = ((y - currentThumb / 2) / currentTravel).coerceIn(0f, 1f)
                            target = Jump((dragFraction * currentRange).roundToInt())
                        }
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            dragging = true
                            follow(down.position.y)
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    follow(change.position.y)
                                }
                            } finally {
                                dragging = false
                            }
                        }
                    },
            )
        }
    }
}

/** The month whose header is at [index] or comes last before it. */
internal fun monthAt(starts: IntArray, index: Int): Int {
    var lo = 0
    var hi = starts.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (starts[mid] <= index) lo = mid else hi = mid - 1
    }
    return lo
}

/** A new object for each move of the finger, so even a move back to the same item is followed. */
private class Jump(val index: Int)

private val THUMB_HEIGHT = 44.dp
