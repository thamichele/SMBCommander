package io.github.thamichele.smbcommander.ui

import io.github.thamichele.smbcommander.data.WakeOnLan

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.core.NasServer
import io.github.thamichele.smbcommander.ui.theme.AccentStore
import io.github.thamichele.smbcommander.ui.theme.AccentTheme
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioType
import io.github.thamichele.smbcommander.widget.BackupWidget

@Composable
fun SetupScreen(vm: AppViewModel) {
    val saved = remember { vm.savedServer() }
    val connecting by vm.connecting.collectAsStateWithLifecycle()
    val withoutConfirm by vm.deleteWithoutConfirm.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf(saved?.host.orEmpty()) }
    var user by rememberSaveable { mutableStateOf(saved?.username.orEmpty()) }
    // the saved password is never shown again: left empty, the saved one is kept
    var password by rememberSaveable { mutableStateOf("") }
    var domain by rememberSaveable { mutableStateOf(saved?.domain.orEmpty()) }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    val found by vm.found.collectAsStateWithLifecycle()
    val finding by vm.finding.collectAsStateWithLifecycle()
    val ready = host.isNotBlank() && user.isNotBlank() && !connecting
    val submit: () -> Unit = { vm.connect(NasServer(host.trim(), user.trim(), password, domain.trim())) }

    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
        if (saved != null) {
            PageHeader(
                context = "SMBCommander",
                title = "Impostazioni",
                navigation = { ToolButton(AppIcons.Back, "Indietro", { vm.back() }) },
            )
        } else {
            PageHeader(context = "SMBCommander", title = "Collega il NAS")
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (saved != null) SectionLabel("NAS", Modifier.padding(top = 8.dp))
            Text(
                "Indirizzo del NAS nella rete di casa e un utente che può accedere alle cartelle condivise. " +
                    "I dati restano cifrati su questo telefono.",
                style = MaterialTheme.typography.bodyMedium,
                color = Arancio.colors.textSecondary,
            )
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Indirizzo IP o nome del NAS") },
                singleLine = true,
                textStyle = ArancioType.code.copy(fontSize = 15.sp),
                shape = ArancioShape.control,
                colors = arancioFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            // NAS that announce themselves on the Wi-Fi (Bonjour): one tap fills the address
            Row(verticalAlignment = Alignment.CenterVertically) {
                GhostButton(onClick = { vm.findNas() }, enabled = !finding) {
                    Text(if (finding) "Cerco nella rete…" else "Cerca il NAS nella rete")
                }
                if (finding) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Arancio.colors.accent)
            }
            found?.let { list ->
                if (list.isEmpty() && !finding) {
                    Text(
                        "Nessun NAS si è presentato. Su QNAP serve il servizio Bonjour (Pannello di controllo › Servizi di rete).",
                        style = MaterialTheme.typography.bodySmall,
                        color = Arancio.colors.textSecondary,
                    )
                }
                list.forEach { nas ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(ArancioShape.control)
                            .background(if (host == nas.address) Arancio.colors.accentTint else Arancio.colors.surface)
                            .border(1.dp, if (host == nas.address) Arancio.colors.accentBorder else Arancio.colors.border, ArancioShape.control)
                            .clickable { host = nas.address }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Server, contentDescription = null, tint = Arancio.colors.accentText, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(nas.name, style = MaterialTheme.typography.titleSmall)
                            Text(nas.address, style = ArancioType.data, color = Arancio.colors.textSecondary)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Utente") },
                singleLine = true,
                shape = ArancioShape.control,
                colors = arancioFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(if (saved != null) "Password (vuota = quella salvata)" else "Password") },
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
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("Dominio o gruppo di lavoro (facoltativo)") },
                singleLine = true,
                shape = ArancioShape.control,
                colors = arancioFieldColors(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            PrimaryButton(
                onClick = submit,
                enabled = ready,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (connecting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Arancio.colors.onAccent,
                    )
                } else {
                    Text(if (saved != null) "Salva e ricollega" else "Collega")
                }
            }
            if (saved != null) {
                ArancioButton(onClick = { confirmForget = true }, kind = ButtonKind.Danger, modifier = Modifier.fillMaxWidth()) {
                    Text("Scollega e dimentica questo NAS")
                }
                OtherNasSection(vm)
                SectionLabel("Galleria", Modifier.padding(top = 20.dp))
                Panel(padding = PaddingValues(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setDeleteWithoutConfirm(!withoutConfirm) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Elimina senza conferma", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Nell'elenco della Galleria, una foto fatta scorrere verso sinistra viene eliminata subito " +
                                    "dal NAS. Se è spento, prima chiede conferma.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Arancio.colors.textSecondary,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(
                            checked = withoutConfirm,
                            onCheckedChange = vm::setDeleteWithoutConfirm,
                            colors = arancioSwitchColors(),
                        )
                    }
                }
            }
            if (saved != null) OptionalSection(vm)
            AppearanceSection()
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmForget) {
        ConfirmDialog(
            title = "Dimenticare il NAS?",
            text = "Indirizzo, utente, password, gli altri NAS e l'elenco delle tue cartelle vengono cancellati da questo telefono. I file sul NAS non vengono toccati.",
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

/** Functions that not everyone wants: all off until turned on here. */
@Composable
private fun OptionalSection(vm: AppViewModel) {
    val extras by vm.extras.collectAsStateWithLifecycle()
    var mac by rememberSaveable { mutableStateOf(extras.wakeMac) }
    SectionLabel("Funzioni facoltative", Modifier.padding(top = 20.dp))
    Text("Tutte spente finché non le accendi.", style = MaterialTheme.typography.bodySmall, color = Arancio.colors.textSecondary)
    OptionRow(
        "Blocca con impronta o blocco schermo",
        "All'apertura e dopo mezzo minuto fuori dall'app chiede impronta, volto o PIN del telefono. Le altre app " +
            "vedono il NAS nel selettore file solo nei 10 minuti dopo lo sblocco.",
        extras.lock,
        vm::setLock,
    )
    OptionRow(
        "Accendi il NAS dall'app (Wake-on-LAN)",
        "Aggiunge «Accendi il NAS» in Le mie cartelle e quando il NAS non risponde. Va attivato anche sul NAS " +
            "(su QNAP: Pannello di controllo › Hardware › Wake on LAN).",
        extras.wakeOnLan,
        vm::setWakeOnLan,
    )
    if (extras.wakeOnLan) {
        OutlinedTextField(
            value = mac,
            onValueChange = { mac = it; vm.setWakeMac(it) },
            label = { Text("Indirizzo MAC del NAS (00:11:22:33:44:55)") },
            isError = mac.isNotBlank() && WakeOnLan.parseMac(mac) == null,
            singleLine = true,
            textStyle = ArancioType.code.copy(fontSize = 15.sp),
            shape = ArancioShape.control,
            colors = arancioFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    OptionRow(
        "Ritrova il NAS se cambia indirizzo",
        "Se il NAS non risponde, lo cerca nella rete e passa al nuovo indirizzo, ma solo se risponde lo stesso NAS " +
            "abbinato (riconosciuto prima di inviare la password).",
        extras.followNas,
        vm::setFollowNas,
    )
    OptionRow(
        "Avvia subito i video",
        "Un video aperto da un elenco parte da solo a schermo intero, senza toccare Riproduci, e così anche " +
            "quelli a cui arrivi scorrendo.",
        extras.autoplayVideo,
        vm::setAutoplayVideo,
    )
    OptionRow(
        "Mostra NAS e utente sotto le cartelle",
        "In File, sotto ogni cartella, in piccolo: l'indirizzo del NAS su cui si trova e l'utente con cui " +
            "l'app vi accede. Utile con più NAS o più utenti.",
        extras.showNasOnFolders,
        vm::setShowNasOnFolders,
    )
}

@Composable
private fun OptionRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Arancio.colors.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = arancioSwitchColors(),
        )
    }
}

/** "Modalità colori" of the Arancio UI design: the main colour of the app, its widget and its launcher icon. */
@Composable
private fun AppearanceSection() {
    val context = LocalContext.current
    val current by remember(context) { AccentStore.state(context) }.collectAsStateWithLifecycle()
    SectionLabel("Aspetto", Modifier.padding(top = 20.dp))
    Text(
        "Colore principale dell'app, del widget e dell'icona. L'icona cambia quando esci dall'app; " +
            "se l'avevi nella schermata Home, potrebbe servire rimetterla.",
        style = MaterialTheme.typography.bodySmall,
        color = Arancio.colors.textSecondary,
    )
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AccentTheme.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { theme ->
                    val selected = theme == current
                    // as the radio buttons of the "Modalità colori" board: tinted ground and ring of the colour when chosen
                    val shape = ArancioShape.control
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(shape)
                            .background(if (selected) theme.main.copy(alpha = 0.12f) else Arancio.colors.surface)
                            .border(1.dp, if (selected) theme.main else Arancio.colors.border, shape)
                            .selectable(selected = selected, role = Role.RadioButton) {
                                AccentStore.set(context, theme)
                                BackupWidget.updateAll(context)
                            }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(theme.main))
                        Text(theme.label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), color = Arancio.colors.text)
                    }
                }
            }
        }
    }
}
