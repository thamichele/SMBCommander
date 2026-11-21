/*
 * DateScroller.kt - the handle on the right of the gallery that scrolls by date
 *
 * Works on any lazy list or grid through ScrollPosition; it only moves the list, it changes nothing.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.gallery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioType
import io.github.thamichele.smbcommander.ui.theme.JetBrainsMono
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** What the date scroller needs of a lazy list or grid. */
interface ScrollPosition {
    val total: Int
    val visible: Int
    /** Index of the first visible item, plus how much of it is already scrolled away (0..1). */
    val first: Float
    val scrolling: Boolean
    /** Index of the last item at least partly on screen. */
    val last: Int
    /**
     * jumpTo() - scrolls to an item at once
     * @param index the item
     *
     * Context: suspends until the list has moved.
     * Return: nothing.
     */
    suspend fun jumpTo(index: Int)
}

/**
 * rememberScrollPosition() - the scroll position of a lazy list, for the date scroller
 * @param state the state of the list
 *
 * Context: composable.
 * Return: the position, the same object while [state] is the same.
 */
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

/**
 * rememberScrollPosition() - the scroll position of a lazy grid, for the date scroller
 * @param state the state of the grid
 *
 * Context: composable.
 * Return: the position, the same object while [state] is the same.
 */
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
 * DateScroller() - fast scrolling by date along the right edge, as in the phone's own gallery
 * @param position the list or grid it moves
 * @param months the months shown, newest first
 * @param starts the index of the header of each month in the list
 * @param modifier placement
 *
 * A handle appears while the photos scroll; dragging it (or touching the edge while it shows) runs through the whole
 * gallery, with the month and year shown next to the finger.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun DateScroller(position: ScrollPosition, months: List<MonthGroup>, starts: IntArray, modifier: Modifier = Modifier) {
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
            delay(HIDE_DELAY_MS)
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
        if (dragging) YearMarks(months, starts, range, travel, thumbPx)

        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, thumbY.roundToInt()) },
        ) {
            ScrollHandle(dragging, "${monthName(month.month)} ${month.year}")
        }

        // the touch strip exists only while the handle shows, so the photos at the edge stay tappable otherwise
        if (shown) {
            TouchStrip(
                onDragging = { dragging = it },
                onMove = { y ->
                    dragFraction = ((y - currentThumb / 2) / currentTravel).coerceIn(0f, 1f)
                    target = Jump((dragFraction * currentRange).roundToInt())
                },
            )
        }
    }
}

/* The strip along the right edge that follows the finger; onMove gets its height from the top. */
@Composable
private fun BoxScope.TouchStrip(onDragging: (Boolean) -> Unit, onMove: (Float) -> Unit) {
    // read by the gesture without restarting it
    val dragging by rememberUpdatedState(onDragging)
    val follow by rememberUpdatedState(onMove)
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .width(36.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging(true)
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
                        dragging(false)
                    }
                }
            },
    )
}

/* The years along the edge, each where its first month is, left out where they would overlap the one above. */
@Composable
private fun BoxScope.YearMarks(months: List<MonthGroup>, starts: IntArray, range: Int, travel: Float, thumbPx: Float) {
    val c = Arancio.colors
    val density = LocalDensity.current
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
            style = ArancioType.data,
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

/* The handle, and while it is dragged the month it points at next to the finger. */
@Composable
private fun ScrollHandle(dragging: Boolean, label: String) {
    val c = Arancio.colors
    Box(contentAlignment = Alignment.CenterEnd) {
        if (dragging) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono),
                color = c.text,
                modifier = Modifier
                    .padding(end = 48.dp)
                    .shadow(6.dp, ArancioShape.menu, ambientColor = c.shadow, spotColor = c.shadow)
                    .background(c.surfaceRaised, ArancioShape.menu)
                    .border(1.dp, c.borderStrong, ArancioShape.menu)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Box(
            Modifier
                .padding(end = 4.dp)
                .size(width = 28.dp, height = THUMB_HEIGHT)
                .background(if (dragging) c.accent else c.surfaceRaised, RoundedCornerShape(999.dp))
                .border(1.dp, if (dragging) c.accent else c.borderStrong, RoundedCornerShape(999.dp)),
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

/**
 * monthAt() - the month whose header is at an index or comes last before it
 * @param starts the index of the header of each month
 * @param index an item of the list
 *
 * Return: the index of the month in [starts].
 */
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

/** How long the handle stays after the photos stop moving. */
private const val HIDE_DELAY_MS = 1500L
