package io.github.thamichele.smbcommander.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.core.NasShares
import io.github.thamichele.smbcommander.data.OtherNas
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioType

/** Start screen: only the folders the user added, hidden shares included; nothing appears on its own. */
@Composable
fun FoldersScreen(vm: AppViewModel) {
    val extras by vm.extras.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val otherNas by vm.otherNas.collectAsStateWithLifecycle()
    val host = remember { vm.savedHost() }
    val c = Arancio.colors
    var adding by remember { mutableStateOf(false) }
    var addingNas by remember { mutableStateOf(false) }
    var editingNas by remember { mutableStateOf<OtherNas?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageHeader(context = host, title = "Le mie cartelle") {
                if (extras.wakeOnLan) ToolButton(AppIcons.Power, "Accendi il NAS", { vm.wakeNas() })
                ToolButton(AppIcons.Delete, "Cestino", vm::openTrash)
                ToolButton(AppIcons.Server, "Impostazioni", vm::openSettings)
            }
            if (folders.isEmpty()) {
                EmptyState(
                    icon = AppIcons.FolderPlus,
                    title = "Nessuna cartella",
                    text = "Aggiungi le cartelle del NAS che vuoi gestire da qui. Funziona anche con le cartelle " +
                        "condivise nascoste: basta scriverne il nome.",
                ) {
                    PrimaryButton(onClick = { adding = true }) {
                        Icon(AppIcons.FolderPlus, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Aggiungi una cartella")
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(folders, key = { it.fullPath }) { folder ->
                        // reading the list of NAS here draws the card again when a NAS is renamed
                        val nas = otherNas.find { NasShares.nasOf(folder.share) == it.id }
                        FolderCard(
                            folder,
                            title = folder.name,
                            detail = folderDetail(folder, nas),
                            onOpen = { vm.openSaved(folder) },
                            onRemove = { vm.removeFolder(folder) },
                            onEditNas = nas?.takeIf { folder.isNas }?.let { { editingNas = it } },
                        )
                    }
                }
            }
        }
        if (folders.isNotEmpty()) {
            AccentFab(AppIcons.Plus, "Aggiungi una cartella", onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }

    if (adding) {
        AddFolderDialog(vm, onDismiss = { adding = false }, onAddNas = { adding = false; addingNas = true })
    }
    if (addingNas || editingNas != null) {
        NasDialog(vm, editingNas, onDismiss = { addingNas = false; editingNas = null })
    }
}

/* Second line of a card: where the folder is, or the address of a whole other NAS; null when the name says it all. */
private fun folderDetail(folder: SavedFolder, nas: OtherNas?): String? = when {
    folder.isNas -> nas?.server?.host?.let { "NAS · $it" } ?: "NAS"
    folder.shown != folder.name -> folder.shown
    else -> null
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(
    folder: SavedFolder,
    title: String,
    detail: String?,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
    onEditNas: (() -> Unit)?,
) {
    val c = Arancio.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ArancioShape.card)
            .background(c.surface)
            .border(1.dp, c.border, ArancioShape.card)
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).background(c.folder.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (folder.isNas) AppIcons.Server else AppIcons.Folder, contentDescription = null, tint = c.folder, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) {
                Text(
                    detail,
                    style = ArancioType.data,
                    color = c.textSecondary,
                )
            }
        }
        Box {
            ToolButton(AppIcons.More, "Azioni per $title", { menu = true })
            ArancioMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (onEditNas != null) {
                    ArancioMenuItem(AppIcons.Rename, "Modifica il collegamento") { menu = false; onEditNas() }
                }
                ArancioMenuItem(AppIcons.Close, "Togli dall'elenco", danger = true) { menu = false; onRemove() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddFolderDialog(vm: AppViewModel, onDismiss: () -> Unit, onAddNas: () -> Unit) {
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val checking by vm.checkingFolder.collectAsStateWithLifecycle()
    val otherNas by vm.otherNas.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    // null: the main NAS
    var nasId by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(nasId) { vm.loadSuggestions(nasId) }
    val shareOf = { s: String -> if (nasId == null) s else NasShares.of(nasId!!, s) }
    val available = suggestions.filter { s -> folders.none { it.path.isEmpty() && it.share.equals(shareOf(s), ignoreCase = true) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Arancio.colors.surfaceRaised,
        title = { Text("Aggiungi una cartella", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (otherNas.isNotEmpty()) {
                    Text("Su quale NAS", style = MaterialTheme.typography.labelMedium, color = Arancio.colors.textSecondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Pill("Principale", nasId == null) { nasId = null }
                        otherNas.forEach { nas -> Pill(nas.name, nasId == nas.id) { nasId = nas.id } }
                    }
                }
                Text(
                    "Nome della cartella condivisa, anche nascosta, eventualmente seguito da una sottocartella.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Arancio.colors.textSecondary,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("es. Multimedia/Foto") },
                    shape = ArancioShape.control,
                    colors = arancioFieldColors(),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                if (available.isNotEmpty()) {
                    Text(
                        "Condivise visibili sul NAS",
                        style = MaterialTheme.typography.labelMedium,
                        color = Arancio.colors.textSecondary,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        available.forEach { share -> Pill(share, text.trim() == share) { text = share } }
                    }
                }
                GhostButton(onClick = onAddNas) {
                    Icon(AppIcons.Server, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Collega un altro NAS")
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = { vm.addFolder(text, nasId, onAdded = onDismiss) },
                enabled = text.isNotBlank() && !checking,
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Arancio.colors.onAccent)
                } else {
                    Text("Aggiungi")
                }
            }
        },
        dismissButton = { GhostButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
