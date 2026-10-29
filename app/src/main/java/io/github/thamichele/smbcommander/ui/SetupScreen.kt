package io.github.thamichele.smbcommander.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.core.NasServer
import io.github.thamichele.smbcommander.ui.theme.Arancio

@Composable
fun SetupScreen(vm: AppViewModel) {
    val saved = remember { vm.savedServer() }
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf(saved?.host.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(saved?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(saved?.password.orEmpty()) }
    var domain by rememberSaveable { mutableStateOf(saved?.domain.orEmpty()) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val ready = host.isNotBlank() && user.isNotBlank() && !connecting
    val submit: () -> Unit = { vm.connect(NasServer(host.trim(), user.trim(), password, domain.trim())) }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        PageHeader(context = "SMBCommander", title = "Collega il NAS")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "Indirizzo del NAS nella rete di casa e un utente che può accedere alle cartelle condivise. " +
                    "I dati restano cifrati su questo telefono.",
                style = MaterialTheme.typography.bodyLarge,
                color = Arancio.colors.textSecondary,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Indirizzo IP o nome del NAS") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = arancioFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Utente") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = arancioFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = arancioFieldColors(),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) AppIcons.EyeOff else AppIcons.Eye,
                            contentDescription = if (showPassword) "Nascondi password" else "Mostra password",
                            modifier = Modifier.size(22.dp),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("Dominio o gruppo di lavoro (facoltativo)") },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                colors = arancioFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = submit,
                enabled = ready,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Arancio.colors.onAccent,
                    )
                } else {
                    Text("Collega")
                }
            }
            if (saved != null) {
                TextButton(onClick = { confirmForget = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Scollega e dimentica questo NAS", color = Arancio.colors.danger)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "Dimenticare il NAS?",
            text = "Indirizzo, utente e password vengono cancellati da questo telefono. I file sul NAS non vengono toccati.",
            confirm = "Dimentica",
            danger = true,
            onDismiss = { confirmForget = false },
            onConfirm = {
                confirmForget = false
                host = ""; user = ""; password = ""; domain = ""
                vm.disconnect()
            },
        )
    }
}
