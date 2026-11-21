/*
 * BrowserScreen.kt - the file browser: one folder of the NAS, its files and what can be done with them
 *
 * Draws the list or grid, the selection, the search and the dialogs; every action is AppViewModel's.
 *
 * Version: 1.28.6
 */
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import io.github.thamichele.smbcommander.core.FileTrash
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.NasShares
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.core.ZipArchive
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.data.hasPicture
import io.github.thamichele.smbcommander.data.isAudio
import io.github.thamichele.smbcommander.data.isMedia
import io.github.thamichele.smbcommander.data.isVideo
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioType

private sealed interface Dialog {
    data object NewFolder : Dialog
    data object NewTextFile : Dialog
    data object DeleteSelected : Dialog
    data class Rename(val entry: RemoteEntry) : Dialog
    data class Delete(val entry: RemoteEntry) : Dialog
    data class Info(val entry: RemoteEntry) : Dialog
}

/**
 * BrowserScreen() - one folder of the NAS, its files and what can be done with them
 * @param vm the folder, the selection, the clipboard and every action
 * @param screen which folder
 *
 * The header changes with the mode: the selection, the search, or the folder with its sorting and actions.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun BrowserScreen(vm: AppViewModel, screen: Screen.Browser) {
    val state by vm.browser.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val clipboard by vm.clipboard.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteEntry?>(null) }

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) vm.downloadTo(entry, uri)
    }
    val actions = EntryActions(
        selected = selected,
        open = { openEntry(vm, it, selecting = selected.isNotEmpty()) },
        edit = { vm.open(it, edit = true) },
        download = { pendingDownload = it; saveFile.launch(it.name) },
        share = { vm.share(listOf(it)) },
        rename = { dialog = Dialog.Rename(it) },
        delete = { dialog = Dialog.Delete(it) },
        info = { dialog = Dialog.Info(it) },
        select = vm::toggleSelected,
    )
    val listState = remember(screen) { vm.scrollOf(screen).let { (i, o) -> LazyListState(i, o) } }
    val gridState = remember(screen) { vm.scrollOf(screen).let { (i, o) -> LazyGridState(i, o) } }
    KeepScroll(vm, screen, state.grid, listState, gridState)

    // while the files scroll down the header of the folder shrinks to its buttons, and grows back scrolling up
    val collapse = rememberCollapsingHeaderState()
    LaunchedEffect(screen.path, selected.isEmpty(), state.searching) { collapse.show() }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().nestedScroll(collapse.connection)) {
            when {
                selected.isNotEmpty() -> SelectionHeader(vm, state, selected, actions) { dialog = Dialog.DeleteSelected }
                state.searching -> SearchBar(state.query, vm::setQuery, onClose = { vm.setSearching(false) }, deep = state.deep)
                else -> FolderHeader(vm, screen, state, collapse) { dialog = Dialog.Info(it) }
            }
            // while searching the row of the header is gone: the scope and the sorting are chosen here, in words
            if (state.searching) SearchScope(vm, state)
            if (state.showingDeep && state.deepBusy) DeepSearchStatus(screen, state)
            FolderContent(vm, screen, state, actions, listState, gridState)
        }

        clipboard?.let { clip ->
            ClipboardBar(clip, onPaste = vm::paste, onCancel = vm::clearClipboard, modifier = Modifier.align(Alignment.BottomCenter))
        }
        if (clipboard == null && selected.isEmpty()) {
            AddButton(vm, Modifier.align(Alignment.BottomEnd).padding(16.dp)) { dialog = it }
        }
    }

    dialog?.let { d -> BrowserDialog(vm, screen, d, selected.size) { dialog = null } }
}

/* Private: a tap on an entry: during a selection it toggles it, otherwise it opens it with what suits it. */
private fun openEntry(vm: AppViewModel, entry: RemoteEntry, selecting: Boolean) {
    when {
        selecting -> vm.toggleSelected(entry)
        entry.isDirectory -> vm.openFolder(entry)
        entry.isMedia -> vm.openViewer(entry)
        entry.isAudio -> vm.playMusic(entry)
        ZipArchive.isZip(entry.name) -> vm.openArchive(entry)
        else -> vm.open(entry, edit = false)
    }
}

/* Each folder keeps its own scroll: going back finds the list where it was left. */
@Composable
private fun KeepScroll(vm: AppViewModel, screen: Screen.Browser, isGrid: Boolean, listState: LazyListState, gridState: LazyGridState) {
    DisposableEffect(screen, isGrid) {
        onDispose {
            if (isGrid) {
                vm.keepScroll(screen, gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
            } else {
                vm.keepScroll(screen, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
            }
        }
    }
}

/* The header while some entries are selected: what can be done with all of them, and more in the menu. */
@Composable
private fun SelectionHeader(
    vm: AppViewModel,
    state: BrowserState,
    selected: Set<String>,
    actions: EntryActions,
    onDelete: () -> Unit,
) {
    var moreMenu by remember { mutableStateOf(false) }
    val single = if (selected.size == 1) state.visible.firstOrNull { it.path in selected } else null
    PageHeader(
        // only the number as title: four big buttons leave little room next to it
        context = if (selected.size == 1) "Selezionato" else "Selezionati",
        title = "${selected.size}",
        navigation = { ToolButton(AppIcons.Close, "Annulla selezione", vm::clearSelection) },
    ) {
        ToolButton(AppIcons.Copy, "Copia", { vm.cut(move = false) })
        ToolButton(AppIcons.Move, "Sposta", { vm.cut(move = true) })
        ToolButton(AppIcons.Delete, "Elimina", onDelete, tint = Arancio.colors.danger)
        Box {
            ToolButton(AppIcons.More, "Altre azioni", { moreMenu = true })
            ArancioMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                MenuItem(AppIcons.Check, "Seleziona tutto") { moreMenu = false; vm.selectAll() }
                val chosen = state.entries.filter { it.path in selected }
                if (chosen.any { !it.isDirectory }) {
                    MenuItem(AppIcons.Share, "Condividi") { moreMenu = false; vm.clearSelection(); vm.share(chosen) }
                }
                single?.let { entry -> SingleEntryItems(vm, entry, actions) { moreMenu = false } }
            }
        }
    }
}

/* The entries of the menu that only make sense for one selected entry. */
@Composable
private fun SingleEntryItems(vm: AppViewModel, entry: RemoteEntry, actions: EntryActions, close: () -> Unit) {
    if (!entry.isDirectory) {
        MenuItem(AppIcons.OpenWith, "Modifica con…") { close(); vm.clearSelection(); actions.edit(entry) }
        MenuItem(AppIcons.Download, "Salva sul telefono") { close(); vm.clearSelection(); actions.download(entry) }
    }
    MenuItem(AppIcons.Rename, "Rinomina") { close(); vm.clearSelection(); actions.rename(entry) }
    MenuItem(AppIcons.Info, "Info") { close(); actions.info(entry) }
}

/* The header of the folder: where it is, the sorting on the left, search, view and the menu on the right. */
@Composable
private fun FolderHeader(
    vm: AppViewModel,
    screen: Screen.Browser,
    state: BrowserState,
    collapse: CollapsingHeaderState,
    onInfo: (RemoteEntry) -> Unit,
) {
    val title = if (screen.path.isEmpty()) NasShares.shownName(screen.share) else RemotePath.name(screen.path)
    val context = when {
        screen.path.isNotEmpty() -> NasShares.display(screen.share, RemotePath.parent(screen.path))
        NasShares.isRoot(screen.share) -> "Altro NAS"
        else -> NasShares.label(screen.share)?.let { "$it · cartella condivisa" } ?: "Cartella condivisa"
    }
    PageHeader(
        context = context,
        title = title,
        navigation = { ToolButton(AppIcons.Back, "Indietro", { vm.back() }) },
        collapse = collapse,
        tabs = {
            ToolButton(AppIcons.SortName, "Ordina per nome", { vm.setSort(SortMode.Name) }, isPrimary = state.sort == SortMode.Name)
            ToolButton(AppIcons.Calendar, "Ordina per data", { vm.setSort(SortMode.Date) }, isPrimary = state.sort == SortMode.Date)
            ToolButton(AppIcons.SortSize, "Ordina per dimensione", { vm.setSort(SortMode.Size) }, isPrimary = state.sort == SortMode.Size)
        },
    ) {
        ToolButton(AppIcons.Search, "Cerca in questa cartella", { vm.setSearching(true) })
        ToolButton(if (state.grid) AppIcons.List else AppIcons.Grid, if (state.grid) "Vista elenco" else "Vista griglia", vm::toggleGrid)
        FolderMenu(vm, screen, state, onInfo)
    }
}

/* The menu of the folder: refresh, system files, pin, info, the bin and the settings. */
@Composable
private fun FolderMenu(vm: AppViewModel, screen: Screen.Browser, state: BrowserState, onInfo: (RemoteEntry) -> Unit) {
    val folders by vm.folders.collectAsStateWithLifecycle()
    val pinned = folders.any { it.share == screen.share && it.path == screen.path }
    var moreMenu by remember { mutableStateOf(false) }
    Box {
        ToolButton(AppIcons.More, "Altre azioni", { moreMenu = true })
        ArancioMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
            MenuItem(AppIcons.Refresh, "Aggiorna") { moreMenu = false; vm.refresh() }
            MenuItem(
                if (state.showHidden) AppIcons.EyeOff else AppIcons.Eye,
                if (state.showHidden) "Nascondi file di sistema" else "Mostra file di sistema",
            ) { moreMenu = false; vm.toggleHidden() }
            if (!pinned) {
                MenuItem(AppIcons.FolderPlus, "Aggiungi alle mie cartelle") { moreMenu = false; vm.pinCurrent() }
            }
            MenuItem(AppIcons.Info, "Info su questa cartella") {
                moreMenu = false
                onInfo(RemoteEntry(screen.share, screen.path, isDirectory = true, size = 0, modified = 0))
            }
            MenuItem(AppIcons.Delete, "Cestino") { moreMenu = false; vm.openTrash() }
            MenuItem(AppIcons.Settings, "Impostazioni") { moreMenu = false; vm.openSettings() }
        }
    }
}

/* While searching: this folder or also the subfolders, and the sorting, in words. */
@Composable
private fun SearchScope(vm: AppViewModel, state: BrowserState) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 12.dp),
    ) {
        item { Pill("Questa cartella", !state.deep) { vm.setDeepSearch(false) } }
        item { Pill("Anche nelle sottocartelle", state.deep) { vm.setDeepSearch(true) } }
        item { Pill("Nome", state.sort == SortMode.Name) { vm.setSort(SortMode.Name) } }
        item { Pill("Data", state.sort == SortMode.Date) { vm.setSort(SortMode.Date) } }
        item { Pill("Dimensione", state.sort == SortMode.Size) { vm.setSort(SortMode.Size) } }
    }
}

/* The search in the subfolders is still walking: which folder it reads and how many it found. */
@Composable
private fun DeepSearchStatus(screen: Screen.Browser, state: BrowserState) {
    val c = Arancio.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
        Text(
            "Cerco in " + NasShares.display(screen.share, state.deepFolder) +
                " · trovati ${state.visible.size}",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
        )
    }
}

/* The files of the folder, or what to show instead: the error, the loading, an empty folder or search. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderContent(
    vm: AppViewModel,
    screen: Screen.Browser,
    state: BrowserState,
    actions: EntryActions,
    listState: LazyListState,
    gridState: LazyGridState,
) {
    val visible = state.visible
    PullToRefreshBox(
        isRefreshing = state.loading && state.entries.isNotEmpty(),
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            state.error != null && state.entries.isEmpty() -> FolderError(vm, state.error)
            state.loading && state.entries.isEmpty() -> SkeletonList()
            visible.isEmpty() -> EmptyFolder(state)
            state.grid && !state.showingDeep -> LazyVerticalGrid(
                columns = GridCells.Adaptive(112.dp),
                state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = ::entryKey) { GridTile(it, actions) }
            }
            else -> LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = 96.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = ::entryKey) { entry ->
                    ListRow(entry, actions, if (state.showingDeep) locationOf(entry, screen) else null)
                }
            }
        }
    }
}

/* A match from the subfolders says where it is, below the folder searched. */
private fun locationOf(entry: RemoteEntry, screen: Screen.Browser): String {
    val parent = RemotePath.parent(entry.path)
    val base = RemotePath.normalize(screen.path)
    val rel = if (base.isEmpty()) parent else parent.removePrefix(base).trimStart('/')
    return if (rel.isEmpty()) "In questa cartella" else "In " + rel.replace("/", " / ")
}

/* The folder could not be read: try again, or wake the NAS when Wake-on-LAN is on. */
@Composable
private fun FolderError(vm: AppViewModel, error: String) {
    val extras by vm.extras.collectAsStateWithLifecycle()
    Column {
        ErrorCard(error, "Riprova", vm::refresh)
        if (extras.wakeOnLan) {
            SecondaryButton(onClick = { vm.wakeNas() }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Icon(AppIcons.Power, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Accendi il NAS")
            }
        }
    }
}

/* Nothing to show: an empty folder, a search without matches, or one still walking the subfolders. */
@Composable
private fun EmptyFolder(state: BrowserState) {
    val query = state.query.trim()
    EmptyState(
        icon = if (state.query.isNotBlank()) AppIcons.Search else AppIcons.Folder,
        title = when {
            state.showingDeep && state.deepBusy -> "Cerco nelle sottocartelle…"
            state.query.isNotBlank() -> "Nessun risultato"
            else -> "Cartella vuota"
        },
        text = when {
            state.showingDeep && query.length < MIN_DEEP_QUERY -> "Scrivi almeno due lettere per cercare nelle sottocartelle"
            state.showingDeep && state.deepBusy -> "Cerco «$query» in tutte le sottocartelle…"
            state.showingDeep -> "Nessun elemento contiene «$query», nemmeno nelle sottocartelle"
            state.query.isNotBlank() -> "Nessun elemento contiene «$query»"
            else -> "Aggiungi file dal telefono o crea una cartella con il pulsante +."
        },
    )
}

/* The "+" of the folder: upload files or a folder from the phone, new folder, new text file. */
@Composable
private fun AddButton(vm: AppViewModel, modifier: Modifier, onDialog: (Dialog) -> Unit) {
    var addMenu by remember { mutableStateOf(false) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.upload(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.uploadFolder(it) }
    Box(modifier) {
        AccentFab(AppIcons.Plus, "Aggiungi", onClick = { addMenu = true })
        ArancioMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
            MenuItem(AppIcons.Upload, "Carica file dal telefono") { addMenu = false; pickFiles.launch(arrayOf("*/*")) }
            MenuItem(AppIcons.Folder, "Carica una cartella dal telefono") { addMenu = false; pickFolder.launch(null) }
            MenuItem(AppIcons.FolderPlus, "Nuova cartella") { addMenu = false; onDialog(Dialog.NewFolder) }
            MenuItem(AppIcons.Document, "Nuovo file di testo") { addMenu = false; onDialog(Dialog.NewTextFile) }
        }
    }
}

/* The dialog open over the folder; [close] takes it away before its action runs. */
@Composable
private fun BrowserDialog(vm: AppViewModel, screen: Screen.Browser, dialog: Dialog, selectedCount: Int, close: () -> Unit) {
    when (dialog) {
        Dialog.NewFolder -> NameDialog("Nuova cartella", "", "Crea", close) {
            close()
            vm.createFolder(it)
        }
        Dialog.NewTextFile -> NameDialog("Nuovo file di testo", "Nuovo documento.txt", "Crea", close) {
            close()
            vm.createTextFile(it)
        }
        Dialog.DeleteSelected -> ConfirmDialog(
            title = if (selectedCount == 1) "Eliminare l'elemento selezionato?" else "Eliminare $selectedCount elementi?",
            text = if (FileTrash.isInside(screen.path)) "Verranno eliminati definitivamente dal NAS, cartelle comprese con tutto il loro contenuto."
            else "Andranno nel cestino del NAS, cartelle comprese con tutto il loro contenuto: si possono ripristinare per ${FileTrash.KEEP_DAYS} giorni.",
            confirm = "Elimina",
            danger = true,
            onDismiss = close,
            onConfirm = {
                close()
                vm.deleteSelected()
            },
        )
        is Dialog.Rename -> NameDialog("Rinomina", dialog.entry.name, "Rinomina", close) {
            close()
            vm.rename(dialog.entry, it)
        }
        is Dialog.Delete -> ConfirmDialog(
            title = "Eliminare «${dialog.entry.name}»?",
            text = deleteText(dialog.entry),
            confirm = "Elimina",
            danger = true,
            onDismiss = close,
            onConfirm = {
                close()
                vm.delete(dialog.entry)
            },
        )
        is Dialog.Info -> InfoDialog(dialog.entry, onDismiss = close)
    }
}

/* What deleting one entry does: into the bin, or for good from inside the bin. */
private fun deleteText(entry: RemoteEntry): String = when {
    FileTrash.isInside(entry.path) -> "Verrà eliminato definitivamente dal NAS."
    entry.isDirectory -> "La cartella e tutto il suo contenuto andranno nel cestino del NAS: si possono ripristinare per ${FileTrash.KEEP_DAYS} giorni."
    else -> "Il file andrà nel cestino del NAS: si può ripristinare per ${FileTrash.KEEP_DAYS} giorni."
}

/* Letters needed before the search in the subfolders starts. */
private const val MIN_DEEP_QUERY = 2

/* Key of a row: the shares listed in the folder of another NAS all have the same empty path, so the share is part of it. */
private fun entryKey(entry: RemoteEntry): String = entry.share + "/" + entry.path

private class EntryActions(
    val selected: Set<String>,
    val open: (RemoteEntry) -> Unit,
    val edit: (RemoteEntry) -> Unit,
    val download: (RemoteEntry) -> Unit,
    /** A temporary copy to the share sheet, deleted afterwards. */
    val share: (RemoteEntry) -> Unit,
    val rename: (RemoteEntry) -> Unit,
    val delete: (RemoteEntry) -> Unit,
    /** Path, size, dates and what the file says about itself. */
    val info: (RemoteEntry) -> Unit,
    /** Long press: starts the selection, or adds to it. */
    val select: (RemoteEntry) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(entry: RemoteEntry, actions: EntryActions, location: String? = null) {
    var menu by remember { mutableStateOf(false) }
    val isSelected = entry.path in actions.selected
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isSelected) Arancio.colors.accentSoft else Color.Transparent)
            .combinedClickable(onClick = { actions.open(entry) }, onLongClick = { actions.select(entry) })
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Preview(entry, 44.dp)
            if (isSelected) SelectedMark(Modifier.align(Alignment.TopStart), size = 20.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            if (location != null) {
                Text(location, style = MaterialTheme.typography.bodySmall, color = Arancio.colors.accentText)
            }
            Text(entryDetails(entry), style = ArancioType.data, color = Arancio.colors.textSecondary)
        }
        if (actions.selected.isEmpty()) Box {
            ToolButton(AppIcons.More, "Azioni per ${entry.name}", { menu = true })
            EntryMenu(entry, actions, menu) { menu = false }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridTile(entry: RemoteEntry, actions: EntryActions) {
    val isSelected = entry.path in actions.selected
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) Arancio.colors.accentSoft else Color.Transparent)
            .combinedClickable(onClick = { actions.open(entry) }, onLongClick = { actions.select(entry) }),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Preview(entry, null)
            if (isSelected) SelectedMark(Modifier.align(Alignment.TopStart).padding(6.dp), size = 20.dp)
        }
        Text(
            entry.name,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

/** Thumbnail for photos and videos, kind tile for everything else (and while the thumbnail loads or if it fails). */
@Composable
private fun Preview(entry: RemoteEntry, size: Dp?) {
    val box = if (size != null) Modifier.size(size) else Modifier.fillMaxSize()
    val tileSize = size ?: 112.dp
    if (entry.hasPicture) {
        Box(box) {
            SubcomposeAsyncImage(
                model = Thumb(entry),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                loading = { FallbackTile(entry, size) },
                error = { FallbackTile(entry, size) },
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(if (size != null) 10.dp else 12.dp))
                    .background(Arancio.colors.surface),
            )
            if (entry.isVideo) {
                VideoFrameCycle(entry, Modifier.matchParentSize().clip(RoundedCornerShape(if (size != null) 10.dp else 12.dp)))
            }
            if (entry.isVideo) VideoBadge(Modifier.align(Alignment.Center), size = if (size != null) 18.dp else 32.dp)
        }
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
    ArancioMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (entry.isDirectory) {
            MenuItem(AppIcons.Folder, "Apri") { onDismiss(); actions.open(entry) }
        } else {
            MenuItem(AppIcons.File, "Apri") { onDismiss(); actions.open(entry) }
            MenuItem(AppIcons.OpenWith, "Modifica con…") { onDismiss(); actions.edit(entry) }
            MenuItem(AppIcons.Share, "Condividi") { onDismiss(); actions.share(entry) }
            MenuItem(AppIcons.Download, "Salva sul telefono") { onDismiss(); actions.download(entry) }
        }
        MenuItem(AppIcons.Check, "Seleziona") { onDismiss(); actions.select(entry) }
        MenuItem(AppIcons.Rename, "Rinomina") { onDismiss(); actions.rename(entry) }
        MenuItem(AppIcons.Info, "Info") { onDismiss(); actions.info(entry) }
        MenuItem(AppIcons.Delete, "Elimina", danger = true) { onDismiss(); actions.delete(entry) }
    }
}

/** Shown while entries wait on the clipboard: open the destination folder, then paste. */
@Composable
private fun ClipboardBar(clip: Clipboard, onPaste: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val c = Arancio.colors
    val n = clip.entries.size
    Row(
        modifier
            .padding(16.dp)
            .fillMaxWidth()
            .floating(c)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (if (n == 1) "1 elemento" else "$n elementi") + if (clip.move) " da spostare" else " da copiare",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        GhostButton(onClick = onCancel) { Text("Annulla") }
        Spacer(Modifier.width(4.dp))
        PrimaryButton(onClick = onPaste) { Text("Incolla qui") }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, danger: Boolean = false, onClick: () -> Unit) =
    ArancioMenuItem(icon, label, danger = danger, onClick = onClick)

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit, deep: Boolean) {
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
            placeholder = { Text(if (deep) "Cerca anche nelle sottocartelle" else "Cerca in questa cartella") },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
            trailingIcon = if (query.isNotEmpty()) {
                { FieldButton(AppIcons.Close, "Cancella", { onQuery("") }) }
            } else null,
            shape = ArancioShape.control,
            colors = arancioFieldColors(),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
    }
}
