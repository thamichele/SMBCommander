package io.github.thamichele.smbcommander.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thamichele.smbcommander.core.Conflict
import io.github.thamichele.smbcommander.data.TransferJob
import io.github.thamichele.smbcommander.ui.theme.Arancio

/** The copy, move and upload queue: the job running now, how many wait, and the ones that failed. */
@Composable
fun TransferCard(
    jobs: List<TransferJob>,
    paused: Boolean,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Arancio.colors
    val running = jobs.firstOrNull { it.state == TransferJob.State.Running || it.state == TransferJob.State.Retrying }
    val pausedJob = jobs.firstOrNull { it.state == TransferJob.State.Paused }
    val waiting = jobs.count { it.state == TransferJob.State.Waiting }
    val failed = jobs.filter { it.state == TransferJob.State.Failed }
    val done = jobs.count { it.state == TransferJob.State.Done }
    val active = running != null || waiting > 0 || pausedJob != null
    Column(
        modifier
            .padding(16.dp)
            .fillMaxWidth()
            .background(c.surfaceRaised, RoundedCornerShape(12.dp))
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            running?.state == TransferJob.State.Retrying -> {
                Text(running.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Connessione al NAS persa: riprovo tra ${running.retryIn} s, da dove si era fermato",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.accentText,
                )
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp), color = c.textSecondary, trackColor = c.border)
            }
            running != null -> {
                Text(running.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (running.current.isNotEmpty()) {
                    Text(running.current, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val fraction = running.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp), color = c.accent, trackColor = c.border)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp), color = c.accent, trackColor = c.border)
                }
            }
            paused -> {
                Text("Trasferimenti in pausa", style = MaterialTheme.typography.titleSmall)
                pausedJob?.let {
                    Text("${it.label}: riprende da dove si era fermato", style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            !active -> Text(
                if (failed.isEmpty()) "Trasferimenti completati" else "Trasferimenti terminati con errori",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        failed.forEach { job ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${job.label}: ${job.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onRetry(job.id) }) { Text("Riprova") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    if (waiting > 0) "$waiting in coda" else null,
                    if (done > 0) "$done completati" else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (active) {
                if (paused) TextButton(onClick = onResume) { Text("Riprendi") } else TextButton(onClick = onPause) { Text("Pausa") }
                TextButton(onClick = onCancel) { Text("Annulla", color = c.danger) }
            } else {
                TextButton(onClick = onClose) { Text("Chiudi") }
            }
        }
    }
}

/** Some names are already in the destination: replace them, leave them alone or keep both. */
@Composable
fun ConflictDialog(ask: ConflictAsk, onChoice: (Conflict?) -> Unit) {
    val c = Arancio.colors
    val title = if (ask.names.size == 1) "«${ask.names[0]}» c'è già" else "${ask.names.size} elementi ci sono già"
    val list = ask.names.take(4).joinToString("\n") { "• $it" } + if (ask.names.size > 4) "\n… e altri ${ask.names.size - 4}" else ""
    AlertDialog(
        onDismissRequest = { onChoice(null) },
        containerColor = c.surfaceRaised,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(list, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Le cartelle con lo stesso nome vengono unite: la scelta vale per ogni file al loro interno. " +
                        "Un file viene sostituito solo dopo che la nuova copia è risultata identica all'originale.",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                )
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                TextButton(onClick = { onChoice(Conflict.Replace) }) { Text("Sostituisci", color = c.danger) }
                TextButton(onClick = { onChoice(Conflict.Skip) }) { Text("Salta quelli già presenti") }
                TextButton(onClick = { onChoice(Conflict.KeepBoth) }) { Text("Tieni entrambi (aggiunge un numero)") }
                TextButton(onClick = { onChoice(null) }) { Text("Annulla") }
            }
        },
    )
}
