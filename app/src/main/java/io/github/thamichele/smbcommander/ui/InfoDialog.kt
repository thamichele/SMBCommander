package io.github.thamichele.smbcommander.ui

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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.data.MediaDetails
import io.github.thamichele.smbcommander.data.isImage
import io.github.thamichele.smbcommander.data.isMedia
import io.github.thamichele.smbcommander.data.isVideo
import io.github.thamichele.smbcommander.ui.theme.Arancio

/**
 * Everything known about a file or folder: first its path on the NAS, ready to copy, then size and dates, and for
 * photos and videos what the file itself says (resolution, when it was taken, camera, position).
 */
@Composable
fun InfoDialog(entry: RemoteEntry, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SmbCommanderApp
    val c = Arancio.colors
    val host = remember { app.repository.host().orEmpty() }
    val path = if (entry.path.isEmpty()) entry.share else "${entry.share}/${entry.path}"
    val network = if (host.isEmpty()) null else "\\\\$host\\${entry.share}" + RemotePath.toSmb(entry.path).let { if (it.isEmpty()) "" else "\\$it" }

    var details by remember(entry) { mutableStateOf<List<Pair<String, String>>?>(if (entry.isMedia) null else emptyList()) }
    LaunchedEffect(entry) {
        if (entry.isMedia) details = runCatching { app.repository.io { MediaDetails.read(it, entry) } }.getOrDefault(emptyList())
    }

    val copy = { label: String, text: String ->
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13 and later confirm the copy on their own
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) Toast.makeText(context, "$label copiato", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text(entry.name, style = MaterialTheme.typography.titleLarge) },
        text = {
            SelectionContainer {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    InfoRow("Percorso sul NAS", path, onCopy = { copy("Percorso", path) })
                    network?.let { InfoRow("Percorso di rete", it, onCopy = { copy("Percorso di rete", it) }) }
                    InfoRow("Tipo", kind(entry))
                    if (!entry.isDirectory) {
                        InfoRow(
                            "Dimensione",
                            "${Formatter.formatFileSize(context, entry.size)} (${"%,d".format(java.util.Locale.ITALY, entry.size)} byte)",
                        )
                    }
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
                    val loaded = details
                    if (loaded == null) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                            Text("Leggo i dati del file…", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                        }
                    } else {
                        loaded.forEach { (label, value) ->
                            if (label == "Posizione") InfoRow(label, value, onCopy = { copy("Posizione", value) })
                            else InfoRow(label, value)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun InfoRow(label: String, value: String, onCopy: (() -> Unit)? = null) {
    val c = Arancio.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.textSecondary)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(AppIcons.Copy, contentDescription = "Copia $label", tint = c.accentText, modifier = Modifier.size(20.dp))
            }
        }
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
