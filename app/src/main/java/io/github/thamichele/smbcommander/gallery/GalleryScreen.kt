package io.github.thamichele.smbcommander.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import io.github.thamichele.smbcommander.ui.ConfirmDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.AppViewModel
import io.github.thamichele.smbcommander.ui.ErrorCard
import io.github.thamichele.smbcommander.ui.PageHeader
import io.github.thamichele.smbcommander.ui.ToolButton
import io.github.thamichele.smbcommander.ui.theme.Arancio
import java.text.DateFormatSymbols
import java.util.Locale

/** Only the photos of the backup folder, by month, newest first. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(appVm: AppViewModel, vm: GalleryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Arancio.colors
    LaunchedEffect(Unit) { vm.ensureLoaded() }

    // paths of the selected photos: a long press starts the selection, then taps add or remove
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // photos deleted or reloaded meanwhile leave the selection
    val shown = state.photos
    LaunchedEffect(shown) {
        if (selected.isNotEmpty()) selected = selected intersect shown.mapTo(HashSet()) { it.path }
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    Column(Modifier.fillMaxSize()) {
        if (selected.isEmpty()) {
            PageHeader(context = state.folder?.fullPath ?: "Foto salvate sul NAS", title = "Galleria") {
                if (state.folder != null) ToolButton(AppIcons.Refresh, "Aggiorna", vm::refresh)
            }
        } else {
            PageHeader(
                context = "Galleria",
                title = if (selected.size == 1) "1 foto selezionata" else "${selected.size} foto selezionate",
                navigation = { ToolButton(AppIcons.Close, "Annulla selezione", { selected = emptySet() }) },
            ) {
                ToolButton(AppIcons.Delete, "Elimina dal NAS", { confirmDelete = true }, tint = c.danger)
            }
        }
        val folder = state.folder
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
                ErrorCard(state.error.orEmpty(), "Riprova", vm::refresh)
            state.loading && state.months.isEmpty() ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            state.months.isEmpty() ->
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Nessuna foto ancora nella cartella del backup", style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
                }
            else -> PullToRefreshBox(
                isRefreshing = state.loading,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                val all = state.photos
                // index of each photo in the flat list, so a tap opens the viewer on the right one
                val offsets = state.months.runningFold(0) { acc, m -> acc + m.photos.size }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    state.months.forEachIndexed { m, group ->
                        item(key = "h${group.year}-${group.month}", span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                "${monthName(group.month)} ${group.year} · ${group.photos.size}",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = if (m == 0) 0.dp else 16.dp, bottom = 8.dp),
                            )
                        }
                        itemsIndexed(group.photos, key = { _, photo -> photo.path }) { i, photo ->
                            val index = offsets[m] + i
                            val isSelected = photo.path in selected
                            Box(
                                Modifier
                                    .aspectRatio(1f)
                                    .background(c.surface, RoundedCornerShape(4.dp))
                                    .combinedClickable(
                                        onClick = {
                                            when {
                                                selected.isEmpty() -> appVm.openViewer(all, index)
                                                isSelected -> selected = selected - photo.path
                                                else -> selected = selected + photo.path
                                            }
                                        },
                                        onLongClick = { selected = selected + photo.path },
                                    ),
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
                                if (isSelected) {
                                    Box(
                                        Modifier
                                            .align(Alignment.TopStart)
                                            .padding(4.dp)
                                            .size(22.dp)
                                            .background(c.accent, CircleShape),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(AppIcons.Check, contentDescription = null, tint = c.background, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        val photos = shown.filter { it.path in selected }
        ConfirmDialog(
            title = if (photos.size == 1) "Eliminare la foto dal NAS?" else "Eliminare ${photos.size} foto dal NAS?",
            text = "Verranno eliminate dalla cartella del backup. Le foto sul telefono restano dove sono e il backup " +
                "non le caricherà di nuovo.",
            confirm = "Elimina",
            danger = true,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                selected = emptySet()
                appVm.deletePhotos(photos)
            },
        )
    }
}

private fun monthName(month: Int): String =
    DateFormatSymbols(Locale.getDefault()).months[month - 1].replaceFirstChar { it.titlecase(Locale.getDefault()) }

