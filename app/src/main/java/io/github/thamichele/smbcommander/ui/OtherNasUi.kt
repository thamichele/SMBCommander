/*
 * OtherNasUi.kt - the other NAS in the settings, and the dialog to add or change one
 *
 * Shows the NAS added next to the main one and lets the user add, change or forget them. Connecting, saving and
 * encrypting the password is the job of AppViewModel and NasRepository; this file only draws.
 *
 * Version: 1.26.0
 */
package io.github.thamichele.smbcommander.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.core.NasServer
import io.github.thamichele.smbcommander.data.OtherNas
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioType

private val ROW_PADDING = PaddingValues(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
private val ICON_SIZE = 22.dp
private val SECTION_TOP = 20.dp

/**
 * OtherNasSection() - the "Altri NAS" part of the settings
 * @param vm the view model of the app
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun OtherNasSection(vm: AppViewModel) {
    val list by vm.otherNas.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<OtherNas?>(null) }
    var adding by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf<OtherNas?>(null) }

    SectionLabel("Altri NAS", Modifier.padding(top = SECTION_TOP))
    Text(
        "Ognuno compare in File come una cartella in più, con le sue cartelle condivise dentro. Copia e sposta " +
            "funzionano anche da un NAS all'altro. Backup e Galleria restano sul NAS principale.",
        style = MaterialTheme.typography.bodySmall,
        color = Arancio.colors.textSecondary,
    )
    list.forEach { nas ->
        OtherNasRow(nas, onEdit = { editing = nas }, onForget = { forgetting = nas })
    }
    ArancioButton(onClick = { adding = true }, kind = ButtonKind.Secondary, modifier = Modifier.fillMaxWidth()) {
        Icon(AppIcons.Plus, contentDescription = null, modifier = Modifier.size(ICON_SIZE))
        Spacer(Modifier.width(8.dp))
        Text("Aggiungi un altro NAS")
    }

    if (adding || editing != null) {
        NasDialog(vm, editing, onDismiss = { adding = false; editing = null })
    }
    forgetting?.let { nas ->
        ConfirmDialog(
            title = "Togliere «${nas.name}»?",
            text = "Indirizzo, utente, password e le sue cartelle in File vengono cancellati da questo telefono. " +
                "I file sul NAS non vengono toccati.",
            confirm = "Togli",
            danger = true,
            onDismiss = { forgetting = null },
            onConfirm = {
                forgetting = null
                vm.removeOtherNas(nas)
            },
        )
    }
}

@Composable
private fun OtherNasRow(nas: OtherNas, onEdit: () -> Unit, onForget: () -> Unit) {
    Panel(padding = ROW_PADDING) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.Server, contentDescription = null, tint = Arancio.colors.accentText, modifier = Modifier.size(ICON_SIZE))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(nas.name, style = MaterialTheme.typography.titleSmall)
                Text(nas.server.host, style = ArancioType.data, color = Arancio.colors.textSecondary)
            }
            ToolButton(AppIcons.Rename, "Modifica ${nas.name}", onEdit)
            ToolButton(AppIcons.Delete, "Togli ${nas.name}", onForget, tint = Arancio.colors.danger)
        }
    }
}

/**
 * NasDialog() - adds another NAS, or changes [editing]
 * @param vm the view model of the app
 * @param editing the NAS to change, or null to add a new one
 * @param onDismiss closes the dialog; also called once the NAS is saved
 *
 * The saved password is never shown again: left empty, it is kept.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun NasDialog(vm: AppViewModel, editing: OtherNas?, onDismiss: () -> Unit) {
    val saving by vm.savingNas.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var host by rememberSaveable { mutableStateOf(editing?.server?.host.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(editing?.server?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf(editing?.server?.domain.orEmpty()) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    val ready = host.isNotBlank() && user.isNotBlank() && (editing != null || password.isNotEmpty()) && !saving

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Arancio.colors.surfaceRaised,
        title = {
            Text(if (editing == null) "Aggiungi un altro NAS" else "Modifica «${editing.name}»", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Compare in File come una cartella con questo nome. I dati restano cifrati su questo telefono.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Arancio.colors.textSecondary,
                )
                NasField(name, { name = it }, "Nome della cartella (es. Ufficio)")
                NasField(host, { host = it }, "Indirizzo IP o nome del NAS", code = true, keyboard = KeyboardType.Uri)
                NasField(user, { user = it }, "Utente")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(if (editing != null) "Password (vuota = quella salvata)" else "Password") },
                    singleLine = true,
                    shape = ArancioShape.control,
                    colors = arancioFieldColors(),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    trailingIcon = {
                        FieldButton(
                            if (showPassword) AppIcons.EyeOff else AppIcons.Eye,
                            if (showPassword) "Nascondi password" else "Mostra password",
                            { showPassword = !showPassword },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                NasField(domain, { domain = it }, "Dominio o gruppo di lavoro (facoltativo)")
            }
        },
        confirmButton = {
            PrimaryButton(
                onClick = {
                    val server = NasServer(host.trim(), user.trim(), password, domain.trim())
                    vm.saveOtherNas(editing?.id, name, server, onSaved = onDismiss)
                },
                enabled = ready,
            ) {
                if (saving) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Arancio.colors.onAccent)
                } else {
                    Text(if (editing == null) "Collega" else "Salva e ricollega")
                }
            }
        },
        dismissButton = { GhostButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun NasField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    code: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = if (code) ArancioType.code.copy(fontSize = CODE_FONT_SIZE) else MaterialTheme.typography.bodyLarge,
        shape = ArancioShape.control,
        colors = arancioFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
}

private val CODE_FONT_SIZE = 15.sp
