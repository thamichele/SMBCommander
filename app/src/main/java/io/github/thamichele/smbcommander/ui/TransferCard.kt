package io.github.thamichele.smbcommander.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thamichele.smbcommander.data.TransferJob
import io.github.thamichele.smbcommander.ui.theme.Arancio

/** The copy and move queue: the job running now, how many wait, and the ones that failed. */
@Composable
fun TransferCard(jobs: List<TransferJob>, onCancel: () -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val c = Arancio.colors
    val running = jobs.firstOrNull { it.state == TransferJob.State.Running }
    val waiting = jobs.count { it.state == TransferJob.State.Waiting }
    val failed = jobs.filter { it.state == TransferJob.State.Failed }
    val done = jobs.count { it.state == TransferJob.State.Done }
    val active = running != null || waiting > 0
    Column(
        modifier
            .padding(16.dp)
            .fillMaxWidth()
            .background(c.surfaceRaised, RoundedCornerShape(12.dp))
            .border(1.dp, c.border, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (running != null) {
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
        } else if (!active) {
            Text(
                if (failed.isEmpty()) "Trasferimenti completati" else "Trasferimenti terminati con errori",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        failed.forEach {
            Text("${it.label}: ${it.error}", style = MaterialTheme.typography.bodySmall, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                TextButton(onClick = onCancel) { Text("Annulla", color = c.danger) }
            } else {
                TextButton(onClick = onClose) { Text("Chiudi") }
            }
        }
    }
}
