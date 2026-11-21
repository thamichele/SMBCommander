/*
 * TransferCard.kt - the card of the transfer queue: the job running, those waiting, those failed
 *
 * Draws only; the queue is TransferQueue's.
 *
 * Version: 1.28.3
 */
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
import androidx.compose.ui.unit.dp
import io.github.thamichele.smbcommander.core.Conflict
import io.github.thamichele.smbcommander.data.TransferJob
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioType

/**
 * TransferCard() - the card of the transfer queue
 * @param jobs the jobs, running, waiting and ended
 * @param paused the queue is held
 * @param onCancel stops everything
 * @param onPause holds the queue
 * @param onResume lets it run again
 * @param onRetry runs a failed job again
 * @param onClose closes the card once everything ended
 * @param modifier placement
 *
 * The job running now, how many wait, and the ones that failed.
 *
 * Context: composable.
 * Return: nothing.
 */
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
            .floating(c)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            running?.state == TransferJob.State.Retrying -> {
                Text(running.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    "Connessione al NAS persa: riprovo tra ${running.retryIn} s, da dove si era fermato",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.accentText,
                )
                ArancioProgress(null, Modifier.padding(end = 8.dp), color = c.textSecondary)
            }
            running != null -> {
                Text(running.label, style = MaterialTheme.typography.titleSmall)
                if (running.current.isNotEmpty()) {
                    Text(running.current, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
                ArancioProgress(running.fraction, Modifier.padding(end = 8.dp))
            }
            paused -> {
                Text("Trasferimenti in pausa", style = MaterialTheme.typography.titleSmall)
                pausedJob?.let {
                    Text("${it.label}: riprende da dove si era fermato", style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
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
                    modifier = Modifier.weight(1f),
                )
                GhostButton(onClick = { onRetry(job.id) }) { Text("Riprova") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    if (waiting > 0) "$waiting in coda" else null,
                    if (done > 0) "$done completati" else null,
                ).joinToString(" · "),
                style = ArancioType.data,
                color = c.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (active) {
                if (paused) GhostButton(onClick = onResume) { Text("Riprendi") } else GhostButton(onClick = onPause) { Text("Pausa") }
                GhostButton(onClick = onCancel) { Text("Annulla", color = c.danger) }
            } else {
                GhostButton(onClick = onClose) { Text("Chiudi") }
            }
        }
    }
}

/**
 * ConflictDialog() - asks what to do with names already in the destination
 * @param ask the names and how many elements go there
 * @param onChoice replace, leave alone or keep both; null when the user gives up
 *
 * Context: composable.
 * Return: nothing.
 */
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
                GhostButton(onClick = { onChoice(Conflict.Replace) }) { Text("Sostituisci", color = c.danger) }
                GhostButton(onClick = { onChoice(Conflict.Skip) }) { Text("Salta quelli già presenti") }
                GhostButton(onClick = { onChoice(Conflict.KeepBoth) }) { Text("Tieni entrambi (aggiunge un numero)") }
                GhostButton(onClick = { onChoice(null) }) { Text("Annulla") }
            }
        },
    )
}
