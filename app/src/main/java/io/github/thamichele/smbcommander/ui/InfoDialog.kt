/*
 * InfoDialog.kt - everything known about a file or folder
 *
 * Its path, ready to copy, size and dates, and what photos and videos say about themselves (read by MediaDetails).
 *
 * Version: 1.28.3
 */
package io.github.thamichele.smbcommander.ui

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.DiskSpace
import io.github.thamichele.smbcommander.core.FolderScan
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.NasShares
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.data.MediaDetails
import io.github.thamichele.smbcommander.data.isImage
import io.github.thamichele.smbcommander.data.isMedia
import io.github.thamichele.smbcommander.data.isVideo
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioType
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * InfoDialog() - everything known about a file or folder
 * @param entry the file or folder
 * @param onDismiss closes the dialog
 *
 * First its path on the NAS, ready to copy, then size and dates, and for photos and videos what the file itself says
 * (resolution, when it was taken, camera, position).
 *
 * Context: composable; the details are read from the NAS in the background.
 * Return: nothing.
 */
@Composable
fun InfoDialog(entry: RemoteEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SmbCommanderApp
    val c = Arancio.colors
    val host = remember(entry.share) { app.repository.hostOf(entry.share).orEmpty() }
    val local = NasShares.localOf(entry.share)
    // as it was before there were more NAS ("Multimedia/Foto/a.jpg"), with the name of another NAS in front
    val inShare = if (entry.path.isEmpty()) local else "$local/${entry.path}"
    val path = NasShares.label(entry.share)?.let { nas -> if (inShare.isEmpty()) nas else "$nas: $inShare" } ?: inShare
    // the folder of a whole other NAS has no share: its network path is the NAS alone
    val network = if (host.isEmpty()) null else "\\\\$host" + (if (local.isEmpty()) "" else "\\$local") +
        RemotePath.toSmb(entry.path).let { if (it.isEmpty()) "" else "\\$it" }
    val isNasRoot = NasShares.isRoot(entry.share)

    var details by remember(entry) { mutableStateOf<List<Pair<String, String>>?>(if (entry.isMedia) null else emptyList()) }
    LaunchedEffect(entry) {
        if (entry.isMedia) details = runCatching { app.repository.io { MediaDetails.read(it, entry) } }.getOrDefault(emptyList())
    }

    // a folder: what it holds, all levels, counted while the dialog is open; the disk: how full it is
    val count = rememberFolderCount(entry, isNasRoot, app)
    var space by remember(entry) { mutableStateOf<DiskSpace?>(null) }
    LaunchedEffect(entry) {
        space = runCatching { app.repository.io { it.space(entry.share) } }.getOrNull()
    }

    val copy = { label: String, text: String -> copyText(context, label, text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        title = { Text(entry.name, style = MaterialTheme.typography.titleLarge) },
        text = {
            SelectionContainer {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfoRow("Percorso sul NAS", path, mono = true, onCopy = { copy("Percorso", path) })
                    network?.let { InfoRow("Percorso di rete", it, mono = true, onCopy = { copy("Percorso di rete", it) }) }
                    InfoRow("Tipo", kind(entry))
                    if (!entry.isDirectory) {
                        InfoRow(
                            "Dimensione",
                            "${Formatter.formatFileSize(context, entry.size)} (${"%,d".format(Locale.ITALY, entry.size)} byte)",
                            mono = true,
                        )
                    }
                    if (entry.isDirectory && !isNasRoot) InfoRow("Contenuto, sottocartelle comprese", count.text(context))
                    space?.let { DiskSpaceRows(entry, it) }
                    if (entry.modified > 0) {
                        InfoRow(
                            "Modificato sul NAS",
                            DateUtils.formatDateTime(
                                context,
                                entry.modified,
                                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME,
                            ),
                        )
                    }
                    MediaRows(details, copy)
                }
            }
        },
        confirmButton = { GhostButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

/* What a folder holds, all levels, while it is being counted. */
private class FolderCount(counting: Boolean) {
    var totals by mutableStateOf<FolderScan.Totals?>(null)
    var counting by mutableStateOf(counting)
    var unreadable by mutableIntStateOf(0)

    fun text(context: Context): String {
        val t = totals
        return when {
            t == null && counting -> "Conto i file…"
            t == null -> "Non leggibile"
            else -> buildString {
                append(Formatter.formatFileSize(context, t.bytes))
                append(" · ")
                append(if (t.files == 1) "1 file" else "${"%,d".format(Locale.ITALY, t.files)} file")
                append(", ")
                append(if (t.folders == 1) "1 cartella" else "${"%,d".format(Locale.ITALY, t.folders)} cartelle")
                if (counting) append(" (conto ancora…)")
                if (unreadable > 0) append(" · $unreadable cartelle non leggibili")
            }
        }
    }
}

/* Counts a folder while the dialog is open; nothing for a file or the folder of a whole NAS. */
@Composable
private fun rememberFolderCount(entry: RemoteEntry, isNasRoot: Boolean, app: SmbCommanderApp): FolderCount {
    val count = remember(entry) { FolderCount(counting = entry.isDirectory && !isNasRoot) }
    LaunchedEffect(entry) {
        // the folder of another NAS holds its whole disks: counting them would take hours
        if (!entry.isDirectory || isNasRoot) return@LaunchedEffect
        try {
            withContext(Dispatchers.IO) {
                val fs = app.repository.fs()
                FolderScan.measure(fs, entry.share, entry.path, stopped = { !isActive }, onUnreadable = { count.unreadable++ }) {
                    count.totals = it
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
        } finally {
            count.counting = false
        }
    }
    return count
}

/* How full the disk of the share is, red from nine tenths. */
@Composable
private fun DiskSpaceRows(entry: RemoteEntry, d: DiskSpace) {
    val context = LocalContext.current
    val c = Arancio.colors
    val percent = if (d.total > 0) (d.used * 100 / d.total).toInt() else 0
    InfoRow(
        "Spazio sul disco di ${NasShares.display(entry.share)}",
        "${Formatter.formatFileSize(context, d.free)} liberi su ${Formatter.formatFileSize(context, d.total)} (pieno al $percent%)",
    )
    ArancioProgress(if (d.total > 0) d.used.toFloat() / d.total else 0f, color = if (percent >= FULL_PERCENT) c.danger else c.accent)
}

/* What a photo or video says about itself; null while it is being read. */
@Composable
private fun MediaRows(details: List<Pair<String, String>>?, copy: (String, String) -> Unit) {
    val c = Arancio.colors
    if (details == null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
            Text("Leggo i dati del file…", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
        return
    }
    details.forEach { (label, value) ->
        if (label == "Posizione") InfoRow(label, value, mono = true, onCopy = { copy("Posizione", value) })
        else InfoRow(label, value)
    }
}

/* Puts a text in the clipboard; Android 13 and later confirm the copy on their own. */
private fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "$label copiato", Toast.LENGTH_SHORT).show()
}

/** From this share of the disk in use the bar turns red. */
private const val FULL_PERCENT = 90

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false, onCopy: (() -> Unit)? = null) {
    val c = Arancio.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            Text(value, style = if (mono) ArancioType.code else MaterialTheme.typography.bodyMedium)
        }
        if (onCopy != null) ToolButton(AppIcons.Copy, "Copia $label", onCopy)
    }
}

private fun kind(entry: RemoteEntry): String {
    val ext = entry.extension.uppercase()
    return when {
        entry.isDirectory -> if (entry.path.isEmpty()) "Cartella condivisa" else "Cartella"
        entry.isImage -> "Foto $ext"
        entry.isVideo -> "Video $ext"
        ext.isEmpty() -> "File"
        else -> "File $ext"
    }
}
