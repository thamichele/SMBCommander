/*
 * TrashScreen.kt - the bin of the file browser, every share together
 *
 * Each element goes back where it was or goes for good; the bin itself is FileTrash's.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.core.FileTrash
import io.github.thamichele.smbcommander.core.NasShares
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.ui.theme.Arancio

/**
 * TrashScreen() - what was deleted from the file browser, every share together, most recent first
 * @param vm the bin and its actions
 *
 * Each element goes back where it was, or goes for good. After [FileTrash.KEEP_DAYS] days it goes on its own.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun TrashScreen(vm: AppViewModel) {
    val state by vm.trash.collectAsStateWithLifecycle()
    val c = Arancio.colors
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<FileTrash.Item?>(null) }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            context = "File eliminati",
            title = "Cestino",
            navigation = { ToolButton(AppIcons.Back, "Indietro", { vm.back() }) },
        ) {
            ToolButton(AppIcons.Refresh, "Aggiorna", vm::loadTrash)
        }
        Text(
            "Gli elementi eliminati dalle cartelle restano qui ${FileTrash.KEEP_DAYS} giorni, nascosti sul NAS, poi vengono " +
                "eliminati definitivamente. Le foto della Pulizia rapida hanno il loro cestino, nella Galleria.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (state.items.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                GhostButton(onClick = { vm.restoreFromTrash(state.items) }, enabled = !state.working) { Text("Ripristina tutto") }
                GhostButton(onClick = { confirmEmpty = true }, enabled = !state.working) { Text("Svuota cestino", color = c.danger) }
            }
        }
        when {
            state.error != null && state.items.isEmpty() -> ErrorCard(state.error.orEmpty(), "Riprova", vm::loadTrash)
            state.loading && state.items.isEmpty() -> SkeletonList()
            state.items.isEmpty() -> EmptyState(AppIcons.Delete, "Il cestino è vuoto")
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.share + "/" + it.slot }) { item ->
                    TrashRow(
                        item,
                        enabled = !state.working,
                        onRestore = { vm.restoreFromTrash(listOf(item)) },
                        onDelete = { confirmDelete = item },
                    )
                }
            }
        }
    }

    if (confirmEmpty) {
        ConfirmDialog(
            title = "Svuotare il cestino?",
            text = "${state.items.size} elementi verranno eliminati definitivamente dal NAS, cartelle comprese con tutto il loro contenuto.",
            confirm = "Svuota",
            danger = true,
            onDismiss = { confirmEmpty = false },
            onConfirm = {
                confirmEmpty = false
                vm.deleteFromTrash(state.items)
            },
        )
    }
    confirmDelete?.let { item ->
        ConfirmDialog(
            title = "Eliminare definitivamente «${item.entry.name}»?",
            text = "Non si potrà più ripristinare.",
            confirm = "Elimina",
            danger = true,
            onDismiss = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                vm.deleteFromTrash(listOf(item))
            },
        )
    }
}

/* One element of the bin: where it was, when it was deleted, and its two buttons. */
@Composable
private fun TrashRow(item: FileTrash.Item, enabled: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    val c = Arancio.colors
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindTile(item.entry, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.entry.name, style = MaterialTheme.typography.titleSmall)
            Text(
                "Era in " + NasShares.display(item.share, RemotePath.parent(item.original)),
                style = MaterialTheme.typography.bodySmall,
                color = c.text,
            )
            Text(
                "Eliminato " + DateUtils.getRelativeTimeSpanString(item.deletedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
        GhostButton(onClick = onRestore, enabled = enabled) { Text("Ripristina") }
        ToolButton(AppIcons.Delete, "Elimina definitivamente ${item.entry.name}", onDelete, tint = c.danger)
    }
}
