package io.github.thamichele.smbcommander.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.data.isVideo
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.AppViewModel
import io.github.thamichele.smbcommander.ui.ConfirmDialog
import io.github.thamichele.smbcommander.ui.ErrorCard
import io.github.thamichele.smbcommander.ui.PageHeader
import io.github.thamichele.smbcommander.ui.ToolButton
import io.github.thamichele.smbcommander.ui.VideoBadge
import io.github.thamichele.smbcommander.ui.entryDetails
import io.github.thamichele.smbcommander.ui.theme.Arancio
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
                    ToolButton(
                        if (state.listView) AppIcons.Grid else AppIcons.List,
                        if (state.listView) "Mostra a griglia" else "Mostra a elenco",
                        vm::toggleView,
                    )
                    ToolButton(AppIcons.Refresh, "Aggiorna", vm::pull)
                }
            }
        } else {
            PageHeader(
                context = "Galleria",
                title = if (selected.size == 1) "1 foto selezionata" else "${selected.size} foto selezionate",
                navigation = { ToolButton(AppIcons.Close, "Annulla selezione", { selected = emptySet() }) },
            ) {
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
                if (state.listView) {
                    PhotoList(state.months, selected, onTap, onLongPress, onSwipe)
                } else {
                    PhotoGrid(state.months, selected, onTap, onLongPress)
                }
            }
        }
    }

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
    LazyVerticalGrid(
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
}

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
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), modifier = Modifier.fillMaxSize()) {
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

@Composable
private fun SelectedMark(modifier: Modifier) {
    val c = Arancio.colors
    Box(modifier.size(22.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Check, contentDescription = null, tint = c.background, modifier = Modifier.size(14.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.pressable(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    combinedClickable(onClick = onClick, onLongClick = onLongClick)

private fun monthName(month: Int): String =
    DateFormatSymbols(Locale.getDefault()).months[month - 1].replaceFirstChar { it.titlecase(Locale.getDefault()) }
