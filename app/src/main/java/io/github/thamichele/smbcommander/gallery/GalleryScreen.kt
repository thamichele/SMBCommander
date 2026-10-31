package io.github.thamichele.smbcommander.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.data.isVideo
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.AppViewModel
import io.github.thamichele.smbcommander.ui.ConfirmDialog
import io.github.thamichele.smbcommander.ui.ErrorCard
import io.github.thamichele.smbcommander.ui.InfoDialog
import io.github.thamichele.smbcommander.ui.PageHeader
import io.github.thamichele.smbcommander.ui.SelectedMark
import io.github.thamichele.smbcommander.ui.ToolButton
import io.github.thamichele.smbcommander.ui.VideoBadge
import io.github.thamichele.smbcommander.ui.entryDetails
import io.github.thamichele.smbcommander.ui.theme.Arancio
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormatSymbols
import java.util.Locale
import kotlin.math.roundToInt

/** Only the photos of the backup folder, by month, newest first: as a grid, or as a list where a swipe deletes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(appVm: AppViewModel, vm: GalleryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val withoutConfirm by appVm.deleteWithoutConfirm.collectAsStateWithLifecycle()
    val c = Arancio.colors
    // each time the gallery comes into view: what the phone knows at once, then the NAS's news
    LifecycleResumeEffect(Unit) {
        vm.ensureLoaded()
        onPauseOrDispose {}
    }

    // paths of the selected photos: a long press starts the selection, then taps add or remove
    var selected by remember { mutableStateOf(emptySet<String>()) }
    // photos waiting for "Elimina" in the confirmation dialog
    var toDelete by remember { mutableStateOf<List<RemoteEntry>>(emptyList()) }
    var info by remember { mutableStateOf<RemoteEntry?>(null) }
    // photos deleted or reloaded meanwhile leave the selection
    val shown = state.photos
    LaunchedEffect(shown) {
        if (selected.isNotEmpty()) selected = selected intersect shown.mapTo(HashSet()) { it.path }
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    val onTap: (RemoteEntry, Int) -> Unit = { photo, index ->
        when {
            selected.isEmpty() -> appVm.openViewer(shown, index)
            photo.path in selected -> selected = selected - photo.path
            else -> selected = selected + photo.path
        }
    }
    val onLongPress: (RemoteEntry) -> Unit = { photo -> selected = selected + photo.path }
    /** Returns true when the photo goes at once, false when the dialog asks first. */
    val onSwipe: (RemoteEntry) -> Boolean = { photo ->
        if (withoutConfirm) {
            appVm.deletePhotos(listOf(photo))
            true
        } else {
            toDelete = listOf(photo)
            false
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (selected.isEmpty()) {
            PageHeader(context = state.folder?.fullPath ?: "Foto salvate sul NAS", title = "Galleria") {
                if (state.folder != null) {
                    ViewMenu(state.view, vm::setView)
                    ToolButton(AppIcons.Broom, "Pulizia rapida", appVm::openCleanup)
                    ToolButton(AppIcons.Refresh, "Aggiorna", vm::pull)
                }
            }
        } else {
            PageHeader(
                context = "Galleria",
                title = if (selected.size == 1) "1 foto selezionata" else "${selected.size} foto selezionate",
                navigation = { ToolButton(AppIcons.Close, "Annulla selezione", { selected = emptySet() }) },
            ) {
                if (selected.size == 1) {
                    ToolButton(AppIcons.Info, "Informazioni", { info = shown.firstOrNull { it.path in selected } })
                }
                ToolButton(AppIcons.Share, "Condividi", {
                    val photos = shown.filter { it.path in selected }
                    selected = emptySet()
                    appVm.share(photos)
                })
                ToolButton(
                    AppIcons.Delete,
                    "Elimina dal NAS",
                    { toDelete = shown.filter { it.path in selected } },
                    tint = c.danger,
                )
            }
        }
        val folder = state.folder
        // a quiet refresh: a thin bar, the photos stay where they are
        if (state.loading && !state.pulling && state.months.isNotEmpty()) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = c.accent, trackColor = c.background)
        }
        if (state.error != null && state.months.isNotEmpty()) {
            Text(
                state.error.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when {
            folder == null -> Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "La galleria mostra la cartella in cui il backup salva le foto. Scegli prima quella cartella nel backup.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.textSecondary,
                )
                Button(onClick = appVm::openBackup, shape = RoundedCornerShape(8.dp)) { Text("Vai al backup foto") }
            }
            state.error != null && state.months.isEmpty() ->
                ErrorCard(state.error.orEmpty(), "Riprova", vm::pull)
            state.loading && state.months.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            state.months.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Nessuna foto ancora nella cartella del backup", style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
                }
            else -> PullToRefreshBox(
                isRefreshing = state.pulling,
                onRefresh = vm::pull,
                modifier = Modifier.fillMaxSize(),
            ) {
                when (state.view) {
                    GalleryView.List -> PhotoList(state.months, selected, onTap, onLongPress, onSwipe)
                    GalleryView.Mosaic -> PhotoMosaic(state.months, selected, onTap, onLongPress)
                    GalleryView.Grid -> PhotoGrid(state.months, selected, onTap, onLongPress)
                }
            }
        }
    }

    info?.let { InfoDialog(it, onDismiss = { info = null }) }

    if (toDelete.isNotEmpty()) {
        val photos = toDelete
        ConfirmDialog(
            title = if (photos.size == 1) "Eliminare «${photos[0].name}» dal NAS?" else "Eliminare ${photos.size} foto dal NAS?",
            text = "Verranno eliminate dalla cartella del backup. Le foto sul telefono restano dove sono e il backup " +
                "non le caricherà di nuovo.",
            confirm = "Elimina",
            danger = true,
            onDismiss = { toDelete = emptyList() },
            onConfirm = {
                toDelete = emptyList()
                selected = emptySet()
                appVm.deletePhotos(photos)
            },
        )
    }
}

@Composable
private fun PhotoGrid(
    months: List<MonthGroup>,
    selected: Set<String>,
    onTap: (RemoteEntry, Int) -> Unit,
    onLongPress: (RemoteEntry) -> Unit,
) {
    val c = Arancio.colors
    // index of each photo in the flat list, so a tap opens the viewer on the right one
    val offsets = months.runningFold(0) { acc, m -> acc + m.photos.size }
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = state,
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            months.forEachIndexed { m, group ->
                item(key = "h${group.year}-${group.month}", span = { GridItemSpan(maxLineSpan) }) {
                    MonthHeader(group, first = m == 0)
                }
                itemsIndexed(group.photos, key = { _, photo -> photo.path }) { i, photo ->
                    val isSelected = photo.path in selected
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .background(c.surface, RoundedCornerShape(4.dp))
                            .pressable(onClick = { onTap(photo, offsets[m] + i) }, onLongClick = { onLongPress(photo) }),
                    ) {
                        AsyncImage(
                            model = Thumb(photo),
                            contentDescription = photo.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(if (isSelected) 8.dp else 0.dp)
                                .clip(RoundedCornerShape(if (isSelected) 6.dp else 0.dp)),
                        )
                        if (photo.isVideo) VideoBadge(Modifier.align(Alignment.BottomEnd).padding(6.dp))
                        if (isSelected) SelectedMark(Modifier.align(Alignment.TopStart).padding(4.dp))
                    }
                }
            }
        }
        // each month: its header, then its photos
        val position = rememberScrollPosition(state)
        val starts = remember(months) { IntArray(months.size) { m -> m + offsets[m] } }
        val photos = remember(months) { months.flatMap { it.photos } }
        PrefetchThumbnails(position, photos, ahead = 60, photoAt = photoIndexIn(starts, offsets))
        DateScroller(position, months, starts)
    }
}

/** The three ways of showing the gallery, from the button with the current one's icon. */
@Composable
private fun ViewMenu(current: GalleryView, onPick: (GalleryView) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(current.icon, "Visualizzazione: ${current.label}", { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Arancio.colors.surfaceRaised) {
            for (view in GalleryView.entries) {
                val color = if (view == current) Arancio.colors.accent else Arancio.colors.text
                DropdownMenuItem(
                    text = { Text(view.label, style = MaterialTheme.typography.bodyLarge, color = color) },
                    leadingIcon = { Icon(view.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) },
                    trailingIcon = { if (view == current) Icon(AppIcons.Check, contentDescription = null, tint = color, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        open = false
                        onPick(view)
                    },
                    modifier = Modifier.height(48.dp),
                )
            }
        }
    }
}

private val GalleryView.icon: ImageVector
    get() = when (this) {
        GalleryView.Grid -> AppIcons.Grid
        GalleryView.Mosaic -> AppIcons.Mosaic
        GalleryView.List -> AppIcons.List
    }

/** A row of the mosaic: its photos with their shapes, and where it starts in the whole gallery. */
private class MosaicRow(val photos: List<RemoteEntry>, val ratios: List<Float>, val first: Int, val height: Float, val full: Boolean)

/**
 * Lays [photos] out in rows of the given [width] (dp): each photo keeps its own shape, and photos join a row until it
 * is as wide as the screen at about [target] height; then the row is scaled to fill the width exactly. The last row
 * of a month keeps the target height and stays short. Shapes not known yet count as squares.
 */
private fun mosaicRows(photos: List<RemoteEntry>, first: Int, width: Float, target: Float, gap: Float, ratioOf: (RemoteEntry) -> Float?): List<MosaicRow> {
    val rows = ArrayList<MosaicRow>()
    var start = 0
    var sum = 0f
    for (i in photos.indices) {
        // very long panoramas and thin strips are shown a little cropped, so a row never becomes a sliver
        sum += (ratioOf(photos[i]) ?: 1f).coerceIn(0.5f, 3f)
        val count = i - start + 1
        if (sum * target + gap * (count - 1) >= width) {
            val slice = photos.subList(start, i + 1)
            rows += MosaicRow(slice, slice.map { (ratioOf(it) ?: 1f).coerceIn(0.5f, 3f) }, first + start, (width - gap * (count - 1)) / sum, full = true)
            start = i + 1
            sum = 0f
        }
    }
    if (start < photos.size) {
        val slice = photos.subList(start, photos.size)
        rows += MosaicRow(slice, slice.map { (ratioOf(it) ?: 1f).coerceIn(0.5f, 3f) }, first + start, target, full = false)
    }
    return rows
}

@Composable
private fun PhotoMosaic(
    months: List<MonthGroup>,
    selected: Set<String>,
    onTap: (RemoteEntry, Int) -> Unit,
    onLongPress: (RemoteEntry) -> Unit,
) {
    val c = Arancio.colors
    val thumbnails = (LocalContext.current.applicationContext as SmbCommanderApp).thumbnails
    // shapes arrive as thumbnails load: the rows are arranged again at most a few times a second
    var shapes by remember { mutableIntStateOf(thumbnails.ratios.version.value) }
    LaunchedEffect(thumbnails) {
        thumbnails.ratios.version.collect {
            shapes = it
            delay(500)
        }
    }
    val gap = 3f
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth.value - 32f
        val rows = remember(months, width, shapes) {
            val offsets = months.runningFold(0) { acc, m -> acc + m.photos.size }
            months.mapIndexed { m, group -> mosaicRows(group.photos, offsets[m], width, MOSAIC_ROW_HEIGHT, gap, thumbnails::ratio) }
        }
        val state = rememberLazyListState()
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(gap.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            months.forEachIndexed { m, group ->
                item(key = "h${group.year}-${group.month}") { MonthHeader(group, first = m == 0) }
                listItems(rows[m], key = { "r" + it.photos.first().path }) { row ->
                    Row(Modifier.fillMaxWidth().height(row.height.dp), horizontalArrangement = Arrangement.spacedBy(gap.dp)) {
                        row.photos.forEachIndexed { i, photo ->
                            val isSelected = photo.path in selected
                            Box(
                                (if (row.full) Modifier.weight(row.ratios[i]) else Modifier.width((row.ratios[i] * row.height).dp))
                                    .fillMaxHeight()
                                    .background(c.surface, RoundedCornerShape(4.dp))
                                    .pressable(onClick = { onTap(photo, row.first + i) }, onLongClick = { onLongPress(photo) }),
                            ) {
                                AsyncImage(
                                    model = Thumb(photo),
                                    contentDescription = photo.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(if (isSelected) 8.dp else 0.dp)
                                        .clip(RoundedCornerShape(if (isSelected) 6.dp else 4.dp)),
                                )
                                if (photo.isVideo) VideoBadge(Modifier.align(Alignment.BottomEnd).padding(6.dp))
                                if (isSelected) SelectedMark(Modifier.align(Alignment.TopStart).padding(4.dp))
                            }
                        }
                    }
                }
            }
        }
        // each month: its header, then its rows
        val starts = remember(rows) {
            var at = 0
            IntArray(rows.size) { m -> at.also { at += 1 + rows[m].size } }
        }
        val position = rememberScrollPosition(state)
        // each item's first photo: a header gives the first of its month, a row its own first
        val firstPhotos = remember(rows) {
            val offsets = months.runningFold(0) { acc, m -> acc + m.photos.size }
            rows.flatMapIndexed { m, monthRows -> listOf(offsets[m]) + monthRows.map { it.first } }.toIntArray()
        }
        val photos = remember(months) { months.flatMap { it.photos } }
        PrefetchThumbnails(position, photos, ahead = 40) { firstPhotos.getOrElse(it) { photos.size - 1 } }
        DateScroller(position, months, starts)
    }
}

/** Row height the mosaic aims at: bigger than the grid's tiles, and still sharp with the cached thumbnails. */
private const val MOSAIC_ROW_HEIGHT = 150f

@Composable
private fun PhotoList(
    months: List<MonthGroup>,
    selected: Set<String>,
    onTap: (RemoteEntry, Int) -> Unit,
    onLongPress: (RemoteEntry) -> Unit,
    onSwipe: (RemoteEntry) -> Boolean,
) {
    val c = Arancio.colors
    val offsets = months.runningFold(0) { acc, m -> acc + m.photos.size }
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
            months.forEachIndexed { m, group ->
                item(key = "h${group.year}-${group.month}") {
                    Box(Modifier.padding(horizontal = 16.dp)) { MonthHeader(group, first = m == 0) }
                }
                listItemsIndexed(group.photos, key = { _, photo -> photo.path }) { i, photo ->
                    val isSelected = photo.path in selected
                    SwipeToDelete(enabled = selected.isEmpty(), onDelete = { onSwipe(photo) }) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) c.accentSoft else c.background)
                                .pressable(onClick = { onTap(photo, offsets[m] + i) }, onLongClick = { onLongPress(photo) })
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box {
                                AsyncImage(
                                    model = Thumb(photo),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(c.surface),
                                )
                                if (photo.isVideo) VideoBadge(Modifier.align(Alignment.Center), size = 20.dp)
                                if (isSelected) SelectedMark(Modifier.align(Alignment.TopStart).padding(2.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    photo.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    entryDetails(photo),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = c.textSecondary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }
        val position = rememberScrollPosition(state)
    val starts = remember(months) { IntArray(months.size) { m -> m + offsets[m] } }
    val photos = remember(months) { months.flatMap { it.photos } }
    PrefetchThumbnails(position, photos, ahead = 20, photoAt = photoIndexIn(starts, offsets))
    DateScroller(position, months, starts)
    }
}

/**
 * Swipe to the left to delete, as in mail apps. Past 40% of the width it counts; the row stays away when [onDelete]
 * deletes at once, and comes back when a confirmation is asked instead.
 */
@Composable
private fun SwipeToDelete(enabled: Boolean, onDelete: () -> Boolean, content: @Composable () -> Unit) {
    val c = Arancio.colors
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(0) }
    val currentOnDelete by rememberUpdatedState(onDelete)
    Box(Modifier.fillMaxWidth().onSizeChanged { width = it.width }) {
        if (offset.value < 0f) {
            Box(
                Modifier.matchParentSize().background(c.danger).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(AppIcons.Delete, contentDescription = null, tint = c.background, modifier = Modifier.size(22.dp))
            }
        }
        Box(
            Modifier
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .then(
                    if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (width > 0 && offset.value < -width * 0.4f) {
                                        offset.animateTo(-width.toFloat())
                                        if (!currentOnDelete()) offset.animateTo(0f)
                                    } else {
                                        offset.animateTo(0f)
                                    }
                                }
                            },
                            onDragCancel = { scope.launch { offset.animateTo(0f) } },
                        ) { change, dragAmount ->
                            change.consume()
                            scope.launch { offset.snapTo((offset.value + dragAmount).coerceIn(-width.toFloat(), 0f)) }
                        }
                    }
                ),
        ) { content() }
    }
}

@Composable
private fun MonthHeader(group: MonthGroup, first: Boolean) {
    Text(
        "${monthName(group.month)} ${group.year} · ${group.photos.size}",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = if (first) 0.dp else 16.dp, bottom = 8.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.pressable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

internal fun monthName(month: Int): String =
    DateFormatSymbols(Locale.getDefault()).months[month - 1].replaceFirstChar { it.titlecase(Locale.getDefault()) }
