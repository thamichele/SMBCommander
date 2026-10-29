package io.github.thamichele.smbcommander.share

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.PageHeader
import io.github.thamichele.smbcommander.ui.ToolButton
import io.github.thamichele.smbcommander.ui.theme.Arancio

@Composable
fun ShareScreen(vm: ShareViewModel, onDone: (String) -> Unit, onCancel: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val c = Arancio.colors
    BackHandler { if (!vm.up()) onCancel() }

    Column(Modifier.fillMaxSize().background(c.background).statusBarsPadding().navigationBarsPadding()) {
        val folder = state.folder
        val what = if (vm.count == 1) "1 file" else "${vm.count} file"
        PageHeader(
            context = if (folder == null) "Salva sul NAS · $what" else folder.fullPath,
            title = folder?.name ?: "Dove salvare?",
            navigation = { ToolButton(AppIcons.Back, "Indietro", { if (!vm.up()) onCancel() }) },
        )
        when {
            !vm.configured -> Message("Apri prima SMBCommander e collega il NAS.")
            vm.count == 0 -> Message("Non c'è nulla da salvare in quello che hai condiviso.")
            folder == null -> {
                val folders = vm.folders
                if (folders.isEmpty()) {
                    Message("Aggiungi prima una cartella in «Le mie cartelle» dell'app.")
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(folders, key = { it.fullPath }) { f -> FolderRow(f.name, f.fullPath) { vm.open(f) } }
                    }
                }
            }
            else -> {
                Box(Modifier.weight(1f)) {
                    when {
                        state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = c.accent)
                        }
                        else -> LazyColumn(Modifier.fillMaxSize()) {
                            items(state.subfolders, key = { it.path }) { sub ->
                                FolderRow(sub.name, null) { vm.open(SavedFolder(sub.share, sub.path)) }
                            }
                        }
                    }
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
                    val progress = state.progress
                    if (progress != null) {
                        Text(progress, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val fraction = state.fraction
                        if (fraction != null) {
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.border)
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = c.accent, trackColor = c.border)
                        }
                    } else {
                        Button(
                            onClick = { vm.saveHere(onDone) },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Salva qui $what") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(name: String, detail: String?, onClick: () -> Unit) {
    val c = Arancio.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Folder, contentDescription = null, tint = c.folder, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
        }
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = Arancio.colors.textSecondary,
        modifier = Modifier.padding(16.dp),
    )
}
