package io.github.thamichele.smbcommander.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.ThumbnailCache
import io.github.thamichele.smbcommander.ui.theme.Arancio

/** Page header of the kit: small grey context line over a big title, actions on the right. */
@Composable
fun PageHeader(
    context: String,
    title: String,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = if (navigation == null) 16.dp else 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        navigation?.invoke()
        Column(Modifier.weight(1f).padding(start = if (navigation == null) 0.dp else 4.dp)) {
            if (context.isNotEmpty()) {
                Text(
                    context,
                    style = MaterialTheme.typography.bodySmall,
                    color = Arancio.colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

@Composable
fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit, tint: Color = Arancio.colors.text) {
    IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Pill filter chip: filled light when selected, outlined surface otherwise. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Arancio.colors
    val shape = RoundedCornerShape(999.dp)
    TextButton(
        onClick = onClick,
        shape = shape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) c.text else c.surface,
            contentColor = if (selected) c.background else c.text,
        ),
        modifier = Modifier
            .height(36.dp)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.border, shape)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** Square tile with the icon of the entry's kind, tinted by category. */
@Composable
fun KindTile(entry: RemoteEntry, size: androidx.compose.ui.unit.Dp, iconSize: androidx.compose.ui.unit.Dp = 22.dp) {
    val (icon, tint) = kindOf(entry)
    Box(
        Modifier.size(size).background(tint.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun kindOf(entry: RemoteEntry): Pair<ImageVector, Color> {
    val c = Arancio.colors
    return when {
        entry.isDirectory -> AppIcons.Folder to c.folder
        entry.extension in ThumbnailCache.IMAGE_EXTENSIONS -> AppIcons.Image to c.image
        entry.extension in VIDEO -> AppIcons.Video to c.media
        entry.extension in AUDIO -> AppIcons.Music to c.media
        entry.extension in ARCHIVE -> AppIcons.Archive to c.textSecondary
        entry.extension in DOCUMENT -> AppIcons.Document to c.document
        else -> AppIcons.File to c.textSecondary
    }
}

private val VIDEO = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "wmv", "ts", "3gp")
private val AUDIO = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma")
private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")
private val DOCUMENT = setOf(
    "pdf", "txt", "md", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp",
    "json", "xml", "html", "log", "ini", "conf", "yml", "yaml",
)

@Composable
fun entryDetails(entry: RemoteEntry): String {
    val context = LocalContext.current
    val date = if (entry.modified > 0) {
        DateUtils.formatDateTime(
            context,
            entry.modified,
            if (DateUtils.isToday(entry.modified)) DateUtils.FORMAT_SHOW_TIME
            else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
        ).let { if (DateUtils.isToday(entry.modified)) "oggi $it" else it }
    } else ""
    val kind = if (entry.isDirectory) "Cartella" else Formatter.formatShortFileSize(context, entry.size)
    return listOf(kind, date).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** Card for an error with a way out. */
@Composable
fun ErrorCard(text: String, action: String, onAction: () -> Unit) {
    val c = Arancio.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(c.surface, RoundedCornerShape(12.dp))
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Alert, contentDescription = null, tint = c.danger, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
        OutlinedButton(onClick = onAction, shape = RoundedCornerShape(8.dp)) { Text(action) }
    }
}

@Composable
fun arancioFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Arancio.colors.accent,
    unfocusedBorderColor = Arancio.colors.border,
    focusedLabelColor = Arancio.colors.accentText,
    cursorColor = Arancio.colors.accent,
    focusedContainerColor = Arancio.colors.surface,
    unfocusedContainerColor = Arancio.colors.surface,
)

/** Dialog asking for a file or folder name; the part before the extension is preselected. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val stem = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stem))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Arancio.colors.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = arancioFieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(value.text) },
                enabled = value.text.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    danger: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Arancio.colors.surface,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(8.dp),
                colors = if (danger) {
                    ButtonDefaults.buttonColors(containerColor = Arancio.colors.danger, contentColor = Arancio.colors.onAccent)
                } else ButtonDefaults.buttonColors(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Small play mark on the thumbnail of a video. */
@Composable
fun VideoBadge(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Box(modifier.size(size).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Play, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}
