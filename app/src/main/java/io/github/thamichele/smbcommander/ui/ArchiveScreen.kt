package io.github.thamichele.smbcommander.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.ZipArchive
import io.github.thamichele.smbcommander.ui.theme.Arancio

/**
 * Inside a ZIP of the NAS, folder by folder: a file opens with another app after extracting just that file to the
 * phone; "Estrai" puts a file, a folder or everything into the NAS folder of the archive.
 */
@Composable
fun ArchiveScreen(vm: AppViewModel, screen: Screen.Archive) {
    val state by vm.archive.collectAsStateWithLifecycle()
    val c = Arancio.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            context = if (screen.dir.isEmpty()) "Archivio ZIP" else screen.entry.name + " / " + screen.dir.replace("/", " / ").substringBeforeLast(" / ", ""),
            title = if (screen.dir.isEmpty()) screen.entry.name else screen.dir.substringAfterLast('/'),
            navigation = { ToolButton(AppIcons.Back, "Indietro", { vm.back() }) },
        ) {
            if (screen.dir.isEmpty() && state.items.isNotEmpty()) {
                ToolButton(AppIcons.Download, "Estrai tutto sul NAS", { vm.extractFromArchive(null) })
            }
        }
        when {
            state.error != null -> ErrorCard(state.error.orEmpty(), "Riprova") { vm.openArchive(screen.entry) }
            state.loading -> SkeletonList()
            state.items.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("Archivio vuoto", style = MaterialTheme.typography.bodyLarge, color = c.textSecondary)
            }
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                items(state.items, key = { it.path }) { item ->
                    var menu by remember { mutableStateOf(false) }
                    // shown with the same tiles as the NAS files
                    val asEntry = RemoteEntry(screen.entry.share, item.path, item.isDirectory, item.size, item.modified)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { if (item.isDirectory) vm.openArchiveFolder(item) else vm.openFromArchive(item) }
                            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        KindTile(asEntry, 44.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    item.isDirectory -> "Cartella"
                                    item.encrypted -> "Protetto da password"
                                    else -> Formatter.formatFileSize(context, item.size)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = c.textSecondary,
                            )
                        }
                        Box {
                            ToolButton(AppIcons.More, "Azioni per ${item.name}", { menu = true }, tint = c.textSecondary)
                            ArancioMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                if (!item.isDirectory) {
                                    DropdownMenuItem(
                                        text = { Text("Apri") },
                                        leadingIcon = { Icon(AppIcons.File, null) },
                                        onClick = { menu = false; vm.openFromArchive(item) },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Estrai nella cartella del NAS") },
                                    leadingIcon = { Icon(AppIcons.Download, null) },
                                    onClick = { menu = false; vm.extractFromArchive(item) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
