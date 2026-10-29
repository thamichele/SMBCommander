package io.github.thamichele.smbcommander.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.ui.theme.Arancio

@Composable
fun SharesScreen(vm: AppViewModel) {
    val shares by vm.shares.collectAsStateWithLifecycle()
    val error by vm.sharesError.collectAsStateWithLifecycle()
    val loading by vm.sharesLoading.collectAsStateWithLifecycle()
    val host = vm.savedServer()?.host.orEmpty()
    val c = Arancio.colors

    Column(Modifier.fillMaxSize()) {
        PageHeader(context = host, title = "Cartelle condivise") {
            ToolButton(AppIcons.Refresh, "Aggiorna", { vm.loadShares() })
            ToolButton(AppIcons.Server, "Impostazioni del NAS", vm::openSettings)
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.border)
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            error?.let { message ->
                item {
                    ErrorCard(message, "Riprova") { vm.loadShares() }
                }
                item { ManualShare(onOpen = vm::openShare) }
            }
            items(shares, key = { it }) { share ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(c.surface, RoundedCornerShape(12.dp))
                        .border(1.dp, c.border, RoundedCornerShape(12.dp))
                        .clickable { vm.openShare(share) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp).background(c.folder.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(AppIcons.Folder, contentDescription = null, tint = c.folder, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(share, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Icon(AppIcons.ChevronRight, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(20.dp))
                }
            }
            if (!loading && error == null && shares.isEmpty()) {
                item {
                    Text(
                        "Nessuna cartella condivisa visibile per questo utente.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = c.textSecondary,
                    )
                }
                item { ManualShare(onOpen = vm::openShare) }
            }
        }
    }
}

/** Fallback when the NAS does not list its shares: type the name of one directly. */
@Composable
private fun ManualShare(onOpen: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Oppure apri una cartella condivisa scrivendone il nome:",
            style = MaterialTheme.typography.bodySmall,
            color = Arancio.colors.textSecondary,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text("Nome della condivisione") },
                shape = RoundedCornerShape(8.dp),
                colors = arancioFieldColors(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onOpen(name.trim()) },
                enabled = name.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(52.dp),
            ) { Text("Apri") }
        }
    }
}
