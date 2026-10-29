package io.github.thamichele.smbcommander.backup

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.thamichele.smbcommander.data.SavedFolder
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.PageHeader
import io.github.thamichele.smbcommander.ui.theme.Arancio

@Composable
fun BackupScreen(vm: BackupViewModel = viewModel()) {
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val savedCount by vm.savedCount.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(PhotoBackupWorker.hasMediaPermission(context)) }
    var choosingFolder by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = PhotoBackupWorker.hasMediaPermission(context)
        vm.loadAlbums()
    }
    LaunchedEffect(permitted) { if (permitted) vm.loadAlbums() }
    val c = Arancio.colors

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { PageHeader(context = "Dal telefono al NAS", title = "Backup foto") }

        if (!permitted) {
            item {
                Card {
                    Text("Per salvare le foto serve il permesso di leggere la galleria.", style = MaterialTheme.typography.bodyLarge)
                    Button(
                        onClick = { askPermission.launch(PhotoBackupWorker.mediaPermissions()) },
                        shape = RoundedCornerShape(8.dp),
                    ) { Text("Consenti l'accesso alle foto") }
                }
            }
            return@LazyColumn
        }

        item {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Backup automatico", style = MaterialTheme.typography.titleMedium)
                        Text(
                            statusLine(settings, status, savedCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = c.textSecondary,
                        )
                    }
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = vm::setEnabled,
                        enabled = settings.destination != null && settings.albums.isNotEmpty(),
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = c.accent,
                            checkedThumbColor = c.onAccent,
                            uncheckedTrackColor = c.surfaceRaised,
                            uncheckedBorderColor = c.border,
                        ),
                    )
                }
                if (progress.running) {
                    Text(
                        if (progress.total > 0) "In corso: ${progress.done} di ${progress.total}" else "In corso…",
                        style = MaterialTheme.typography.labelMedium,
                        color = c.accentText,
                    )
                    if (progress.total > 0) {
                        LinearProgressIndicator(
                            progress = { progress.done.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth(),
                            color = c.accent,
                            trackColor = c.border,
                        )
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.border)
                    }
                } else if (settings.enabled) {
                    OutlinedButton(onClick = { vm.runNow() }, shape = RoundedCornerShape(8.dp)) { Text("Esegui ora") }
                }
                status.lastError?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(AppIcons.Alert, contentDescription = null, tint = c.danger, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger)
                    }
                }
            }
        }

        item {
            Card(onClick = { choosingFolder = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.Folder, contentDescription = null, tint = c.folder, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Cartella sul NAS", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        Text(
                            settings.destination?.fullPath ?: "Scegli dove salvare le foto",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (settings.destination == null) c.accentText else c.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
        }

        item { SectionTitle("Album da salvare") }
        if (albums.isEmpty()) {
            item {
                Text(
                    "Nessun album trovato sul telefono.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = c.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        items(albums, key = { it.id }) { album ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.toggleAlbum(album.id) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = album.id in settings.albums,
                    onCheckedChange = { vm.toggleAlbum(album.id) },
                    colors = CheckboxDefaults.colors(checkedColor = c.accent, checkmarkColor = c.onAccent, uncheckedColor = c.border),
                )
                Spacer(Modifier.width(8.dp))
                Text(album.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${album.count}", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }

        item { SectionTitle("Opzioni") }
        item { Option("Includi i video", "I video occupano molto spazio sul NAS", settings.includeVideos, vm::setIncludeVideos) }
        item { Option("Dividi per anno e mese", "Per esempio 2026/10", settings.byMonth, vm::setByMonth) }
        item { Option("Solo quando è in carica", "Il backup aspetta che il telefono sia collegato", settings.chargingOnly, vm::setChargingOnly) }
        item {
            Text(
                "Il backup parte da solo ogni ora quando sei sul Wi-Fi di casa e il NAS risponde. " +
                    "Le foto non vengono mai cancellate dal telefono.",
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }

    if (choosingFolder) {
        FolderChooser(
            folders = vm.folders,
            selected = settings.destination,
            onDismiss = { choosingFolder = false },
            onChoose = {
                choosingFolder = false
                vm.setDestination(it)
            },
        )
    }
}

private fun statusLine(settings: BackupSettings, status: BackupStatus, saved: Int): String = when {
    settings.destination == null -> "Scegli prima la cartella sul NAS"
    settings.albums.isEmpty() -> "Scegli almeno un album"
    !settings.enabled -> "Disattivato"
    status.lastRun == 0L -> "Attivo · in attesa del primo backup"
    else -> "Attivo · ultimo controllo " +
        DateUtils.getRelativeTimeSpanString(status.lastRun, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
        " · $saved salvati"
}

@Composable
private fun Card(onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Arancio.colors
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(c.surface, RoundedCornerShape(12.dp))
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
    )
}

@Composable
private fun Option(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Arancio.colors
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent,
                checkedThumbColor = c.onAccent,
                uncheckedTrackColor = c.surfaceRaised,
                uncheckedBorderColor = c.border,
            ),
        )
    }
}

/** Picks the destination among "Le mie cartelle"; a new one is added there first. */
@Composable
private fun FolderChooser(
    folders: List<SavedFolder>,
    selected: SavedFolder?,
    onDismiss: () -> Unit,
    onChoose: (SavedFolder) -> Unit,
) {
    var choice by remember { mutableStateOf(selected) }
    val c = Arancio.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Dove salvare le foto", style = MaterialTheme.typography.titleLarge) },
        text = {
            if (folders.isEmpty()) {
                Text(
                    "Aggiungi prima una cartella in «Le mie cartelle», per esempio una sottocartella «Foto telefono».",
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Column {
                    folders.forEach { f ->
                        Row(
                            Modifier.fillMaxWidth().clickable { choice = f }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = choice == f,
                                onClick = { choice = f },
                                colors = RadioButtonDefaults.colors(selectedColor = c.accent, unselectedColor = c.border),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(f.fullPath, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { choice?.let(onChoose) },
                enabled = choice != null,
                shape = RoundedCornerShape(8.dp),
            ) { Text("Usa questa cartella") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
