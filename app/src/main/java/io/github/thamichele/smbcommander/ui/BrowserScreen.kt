package io.github.thamichele.smbcommander.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.data.ThumbnailCache
import io.github.thamichele.smbcommander.data.isImage
import io.github.thamichele.smbcommander.ui.theme.Arancio

private sealed interface Dialog {
    data object NewFolder : Dialog
    data class Rename(val entry: RemoteEntry) : Dialog
    data class Delete(val entry: RemoteEntry) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: AppViewModel, screen: Screen.Browser) {
    val state by vm.browser.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val pinned = folders.any { it.share == screen.share && it.path == screen.path }
    val c = Arancio.colors
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var pendingDownload by remember { mutableStateOf<RemoteEntry?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.upload(it) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) vm.downloadTo(entry, uri)
    }

    val actions = EntryActions(
        open = {
            when {
                it.isDirectory -> vm.openFolder(it)
                it.isImage -> vm.openViewer(it)
                else -> vm.open(it, edit = false)
            }
        },
        edit = { vm.open(it, edit = true) },
        download = { pendingDownload = it; saveFile.launch(it.name) },
        rename = { dialog = Dialog.Rename(it) },
        delete = { dialog = Dialog.Delete(it) },
    )

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (state.searching) {
                SearchBar(state.query, vm::setQuery, onClose = { vm.setSearching(false) })
            } else {
                val title = if (screen.path.isEmpty()) screen.share else RemotePath.name(screen.path)
                val context = if (screen.path.isEmpty()) "Cartella condivisa" else
                    (listOf(screen.share) + RemotePath.parent(screen.path).split('/').filter { it.isNotEmpty() }).joinToString(" / ")
                PageHeader(
                    context = context,
                    title = title,
                    navigation = { ToolButton(AppIcons.Back, "Indietro", { vm.back() }) },
                ) {
                    ToolButton(AppIcons.Search, "Cerca in questa cartella", { vm.setSearching(true) })
                    ToolButton(if (state.grid) AppIcons.List else AppIcons.Grid, if (state.grid) "Vista elenco" else "Vista griglia", vm::toggleGrid)
                    Box {
                        ToolButton(AppIcons.More, "Altre azioni", { moreMenu = true })
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }, containerColor = c.surfaceRaised) {
                            MenuItem(AppIcons.Refresh, "Aggiorna") { moreMenu = false; vm.refresh() }
                            MenuItem(
                                if (state.showHidden) AppIcons.EyeOff else AppIcons.Eye,
                                if (state.showHidden) "Nascondi file di sistema" else "Mostra file di sistema",
                            ) { moreMenu = false; vm.toggleHidden() }
                            if (!pinned) {
                                MenuItem(AppIcons.FolderPlus, "Aggiungi alle mie cartelle") { moreMenu = false; vm.pinCurrent() }
                            }
                            MenuItem(AppIcons.Server, "Impostazioni del NAS") { moreMenu = false; vm.openSettings() }
                        }
                    }
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp),
            ) {
                item { Pill("Nome", state.sort == SortMode.Name) { vm.setSort(SortMode.Name) } }
                item { Pill("Data", state.sort == SortMode.Date) { vm.setSort(SortMode.Date) } }
                item { Pill("Dimensione", state.sort == SortMode.Size) { vm.setSort(SortMode.Size) } }
            }

            val visible = state.visible
            PullToRefreshBox(
                isRefreshing = state.loading && state.entries.isNotEmpty(),
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.error != null && state.entries.isEmpty() ->
                        ErrorCard(state.error.orEmpty(), "Riprova", vm::refresh)
                    state.loading && state.entries.isEmpty() ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = c.accent)
                        }
                    visible.isEmpty() ->
                        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (state.query.isNotBlank()) "Nessun elemento contiene «${state.query.trim()}»" else "Cartella vuota",
                                style = MaterialTheme.typography.bodyLarge,
                                color = c.textSecondary,
                            )
                        }
                    state.grid -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(112.dp),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(visible, key = { it.path }) { GridTile(it, actions) }
                    }
                    else -> LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(visible, key = { it.path }) { ListRow(it, actions) }
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            FloatingActionButton(
                onClick = { addMenu = true },
                containerColor = c.accent,
                contentColor = c.onAccent,
                shape = RoundedCornerShape(16.dp),
            ) {
                Icon(AppIcons.Plus, contentDescription = "Aggiungi", modifier = Modifier.size(24.dp))
            }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }, containerColor = c.surfaceRaised) {
                MenuItem(AppIcons.Upload, "Carica file dal telefono") { addMenu = false; pickFiles.launch(arrayOf("*/*")) }
                MenuItem(AppIcons.FolderPlus, "Nuova cartella") { addMenu = false; dialog = Dialog.NewFolder }
            }
        }
    }

    when (val d = dialog) {
        Dialog.NewFolder -> NameDialog("Nuova cartella", "", "Crea", { dialog = null }) {
            dialog = null
            vm.createFolder(it)
        }
        is Dialog.Rename -> NameDialog("Rinomina", d.entry.name, "Rinomina", { dialog = null }) {
            dialog = null
            vm.rename(d.entry, it)
        }
        is Dialog.Delete -> ConfirmDialog(
            title = "Eliminare «${d.entry.name}»?",
            text = if (d.entry.isDirectory) "La cartella e tutto il suo contenuto verranno eliminati dal NAS."
            else "Il file verrà eliminato dal NAS.",
            confirm = "Elimina",
            danger = true,
            onDismiss = { dialog = null },
            onConfirm = {
                dialog = null
                vm.delete(d.entry)
            },
        )
        null -> Unit
    }
}

private class EntryActions(
    val open: (RemoteEntry) -> Unit,
    val edit: (RemoteEntry) -> Unit,
    val download: (RemoteEntry) -> Unit,
    val rename: (RemoteEntry) -> Unit,
    val delete: (RemoteEntry) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(entry: RemoteEntry, actions: EntryActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { actions.open(entry) }, onLongClick = { menu = true })
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Preview(entry, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(entryDetails(entry), style = MaterialTheme.typography.bodySmall, color = Arancio.colors.textSecondary, maxLines = 1)
        }
        Box {
            ToolButton(AppIcons.More, "Azioni per ${entry.name}", { menu = true }, tint = Arancio.colors.textSecondary)
            EntryMenu(entry, actions, menu) { menu = false }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(entry: RemoteEntry, actions: EntryActions) {
    var menu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = { actions.open(entry) }, onLongClick = { menu = true }),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Preview(entry, null)
            EntryMenu(entry, actions, menu) { menu = false }
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Thumbnail for photos, kind tile for everything else (and while the thumbnail loads or if it fails). */
@Composable
private fun Preview(entry: RemoteEntry, size: Dp?) {
    val box = if (size != null) Modifier.size(size) else Modifier.fillMaxSize()
    val tileSize = size ?: 112.dp
    if (!entry.isDirectory && entry.extension in ThumbnailCache.IMAGE_EXTENSIONS) {
        SubcomposeAsyncImage(
            model = Thumb(entry),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            loading = { FallbackTile(entry, size) },
            error = { FallbackTile(entry, size) },
            modifier = box.clip(RoundedCornerShape(if (size != null) 10.dp else 12.dp)).background(Arancio.colors.surface),
        )
    } else {
        FallbackTile(entry, size, tileSize)
    }
}

@Composable
private fun FallbackTile(entry: RemoteEntry, size: Dp?, tileSize: Dp = size ?: 112.dp) {
    if (size != null) {
        KindTile(entry, size)
    } else {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val (icon, tint) = kindOf(entry)
            Box(
                Modifier.fillMaxSize().background(tint.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(tileSize / 3))
            }
        }
    }
}

@Composable
private fun EntryMenu(entry: RemoteEntry, actions: EntryActions, expanded: Boolean, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = Arancio.colors.surfaceRaised) {
        if (entry.isDirectory) {
            MenuItem(AppIcons.Folder, "Apri") { onDismiss(); actions.open(entry) }
        } else {
            MenuItem(AppIcons.File, "Apri") { onDismiss(); actions.open(entry) }
            MenuItem(AppIcons.OpenWith, "Modifica con…") { onDismiss(); actions.edit(entry) }
            MenuItem(AppIcons.Download, "Salva sul telefono") { onDismiss(); actions.download(entry) }
        }
        MenuItem(AppIcons.Rename, "Rinomina") { onDismiss(); actions.rename(entry) }
        MenuItem(AppIcons.Delete, "Elimina", danger = true) { onDismiss(); actions.delete(entry) }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) Arancio.colors.danger else Arancio.colors.text
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge, color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) },
        onClick = onClick,
        modifier = Modifier.height(48.dp),
    )
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(AppIcons.Back, "Chiudi ricerca", onClose)
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Cerca in questa cartella") },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (query.isNotEmpty()) {
                { ToolButton(AppIcons.Close, "Cancella", { onQuery("") }, tint = Arancio.colors.textSecondary) }
            } else null,
            shape = RoundedCornerShape(999.dp),
            colors = arancioFieldColors(),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
    }
}
