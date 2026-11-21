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
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioType

/** Start screen: only the folders the user added, hidden shares included; nothing appears on its own. */
@Composable
fun FoldersScreen(vm: AppViewModel) {
    val extras by vm.extras.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val host = remember { vm.savedHost() }
    val c = Arancio.colors
    var adding by remember { mutableStateOf(false) }

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
                        FolderCard(folder, onOpen = { vm.openSaved(folder) }, onRemove = { vm.removeFolder(folder) })
                    }
                }
            }
        }
        if (folders.isNotEmpty()) {
            AccentFab(AppIcons.Plus, "Aggiungi una cartella", onClick = { adding = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
        }
    }

    if (adding) {
        AddFolderDialog(vm, onDismiss = { adding = false })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(folder: SavedFolder, onOpen: () -> Unit, onRemove: () -> Unit) {
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
            Icon(AppIcons.Folder, contentDescription = null, tint = c.folder, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall)
            if (folder.fullPath != folder.name) {
                Text(
                    folder.fullPath,
                    style = ArancioType.data,
                    color = c.textSecondary,
                )
            }
        }
        Box {
            ToolButton(AppIcons.More, "Azioni per ${folder.name}", { menu = true })
            ArancioMenu(expanded = menu, onDismissRequest = { menu = false }) {
                ArancioMenuItem(AppIcons.Close, "Togli dall'elenco", danger = true) { menu = false; onRemove() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddFolderDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val checking by vm.checkingFolder.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.loadSuggestions()
        focus.requestFocus()
    }
    val available = suggestions.filter { s -> folders.none { it.path.isEmpty() && it.share.equals(s, ignoreCase = true) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Arancio.colors.surfaceRaised,
        title = { Text("Aggiungi una cartella", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = { vm.addFolder(text, onAdded = onDismiss) },
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
