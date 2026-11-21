/*
 * DeletionDialog.kt - the window of a deletion of several files, one at a time
 *
 * Shows the file being deleted, the bar and the outcome of each file; the deletion itself is the view model's.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.data.isMedia
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioType
import kotlinx.coroutines.delay

/**
 * DeletionDialog() - the window of a deletion of several files, one at a time
 * @param deletion how far the deletion is
 * @param onStop stops it after the file being deleted now
 * @param onClose closes the window
 *
 * The file being deleted slides in, the bar fills, and each file gets its outcome in the list below. It closes on its
 * own when everything went well; otherwise it stays open on the summary.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun DeletionDialog(deletion: Deletion, onStop: () -> Unit, onClose: () -> Unit) {
    val c = Arancio.colors
    val allGood = deletion.finished && deletion.error == null && deletion.steps.size == deletion.total &&
        deletion.steps.all { it.outcome == DeletionStep.Outcome.Deleted }
    LaunchedEffect(deletion.finished, allGood) {
        if (allGood) {
            delay(CLOSE_DELAY_MS)
            onClose()
        }
    }
    val fraction by animateFloatAsState(
        targetValue = when {
            deletion.preparing != null && deletion.preparingTotal > 0 -> deletion.preparing.toFloat() / deletion.preparingTotal
            deletion.total > 0 -> deletion.steps.size.toFloat() / deletion.total
            else -> 0f
        },
        animationSpec = tween(250),
        label = "deletion progress",
    )

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnBackPress = deletion.finished, dismissOnClickOutside = deletion.finished),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .dialogPanel(c)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                when {
                    !deletion.finished -> "Eliminazione in corso"
                    deletion.stopping -> "Eliminazione interrotta"
                    else -> "Eliminazione completata"
                },
                style = MaterialTheme.typography.titleLarge,
            )

            CurrentStep(deletion)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ArancioProgress(fraction, color = if (deletion.finished && !allGood) c.textSecondary else c.accent)
                Text(
                    if (deletion.preparing != null) deletion.preparingNote
                    else "${deletion.steps.size} di ${deletion.total}" + if (deletion.left > 0) " · ne mancano ${deletion.left}" else "",
                    style = ArancioType.data,
                    color = c.textSecondary,
                )
            }

            if (deletion.steps.isNotEmpty()) StepList(deletion.steps)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (deletion.finished) {
                    GhostButton(onClick = onClose) { Text("Chiudi") }
                } else {
                    GhostButton(onClick = onStop, enabled = !deletion.stopping) {
                        Text(if (deletion.stopping) "Mi fermo dopo questo…" else "Interrompi")
                    }
                }
            }
        }
    }
}

/* The file being deleted now, each new one sliding in from below; then the summary. */
@Composable
private fun CurrentStep(deletion: Deletion) {
    val c = Arancio.colors
    AnimatedContent(
        targetState = deletion.current,
        transitionSpec = {
            (slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220))) togetherWith
                (slideOutVertically(tween(180)) { -it / 2 } + fadeOut(tween(180)))
        },
        contentKey = { it?.let { e -> e.share + "/" + e.path } },
        label = "current file",
    ) { entry ->
        when {
            entry != null -> CurrentFile(entry)
            deletion.preparing != null -> Status(AppIcons.Delete, c.textSecondary, "Preparo l'eliminazione…", progressText(deletion))
            deletion.finished -> Summary(deletion)
            else -> Status(AppIcons.Delete, c.textSecondary, "Un attimo…", null)
        }
    }
}

/* The outcome of each file, newest first, so the confirmation of the file just deleted is always in sight. */
@Composable
private fun StepList(steps: List<DeletionStep>) {
    LazyColumn(
        Modifier.fillMaxWidth().heightIn(max = 220.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(steps.asReversed(), key = { it.entry.share + "/" + it.entry.path }) { step ->
            StepRow(step, Modifier.animateItem())
        }
    }
}

/** How long a deletion that went well stays on screen before the window closes. */
private const val CLOSE_DELAY_MS = 1500L

private fun progressText(deletion: Deletion): String? =
    if (deletion.preparingTotal > 0) "${deletion.preparing} di ${deletion.preparingTotal}" else null

@Composable
private fun CurrentFile(entry: RemoteEntry) {
    val c = Arancio.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(56.dp).clip(ArancioShape.menu).background(c.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            if (entry.isMedia) {
                AsyncImage(Thumb(entry), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
            } else {
                Icon(if (entry.isDirectory) AppIcons.Folder else AppIcons.File, contentDescription = null, tint = c.textSecondary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text("Elimino", style = MaterialTheme.typography.labelMedium, color = c.danger)
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            Text(
                RemotePath.parent(entry.path).ifEmpty { entry.share },
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
            )
        }
    }
}

@Composable
private fun Summary(deletion: Deletion) {
    val c = Arancio.colors
    val skipped = deletion.skipped.size
    val failed = deletion.failed.size
    val parts = buildList {
        add(if (deletion.deleted.size == 1) "1 eliminato" else "${deletion.deleted.size} eliminati")
        if (skipped > 0) add(if (skipped == 1) "1 saltato perché non era più sul NAS" else "$skipped saltati perché non erano più sul NAS")
        if (failed > 0) add(if (failed == 1) "1 non eliminato" else "$failed non eliminati")
        if (deletion.left > 0 && deletion.error == null) add(if (deletion.left == 1) "1 lasciato dov'era" else "${deletion.left} lasciati dov'erano")
    }
    when {
        deletion.error != null -> Status(AppIcons.Alert, c.danger, "Nessun file eliminato", deletion.error)
        failed > 0 -> Status(AppIcons.Alert, c.danger, parts.first(), parts.drop(1).joinToString("\n"))
        else -> Status(AppIcons.Check, c.success, parts.first(), parts.drop(1).joinToString("\n").ifEmpty { null }, pop = true)
    }
}

@Composable
private fun Status(icon: ImageVector, tint: Color, title: String, detail: String?, pop: Boolean = false) {
    val c = Arancio.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(56.dp).clip(ArancioShape.menu).background(c.surfaceRaised), contentAlignment = Alignment.Center) {
            // the final tick grows in, without bounce (the kit allows none)
            val scale = remember { Animatable(if (pop) 0.6f else 1f) }
            LaunchedEffect(pop) { if (pop) scale.animateTo(1f, tween(240, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))) }
            Icon(
                icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(28.dp).graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
        }
    }
}

@Composable
private fun StepRow(step: DeletionStep, modifier: Modifier) {
    val c = Arancio.colors
    val (icon, tint, note) = when (step.outcome) {
        DeletionStep.Outcome.Deleted -> Triple(AppIcons.Check, c.success, "Eliminato")
        DeletionStep.Outcome.Skipped -> Triple(AppIcons.Skip, c.textSecondary, "Non più sul NAS: saltato")
        DeletionStep.Outcome.Failed -> Triple(AppIcons.Alert, c.danger, step.error ?: "Non eliminato")
    }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = note, tint = tint, modifier = Modifier.size(18.dp))
        Column(Modifier.weight(1f)) {
            Text(step.entry.name, style = MaterialTheme.typography.bodyMedium)
            if (step.outcome != DeletionStep.Outcome.Deleted) {
                Text(note, style = MaterialTheme.typography.bodySmall, color = tint)
            }
        }
    }
}
