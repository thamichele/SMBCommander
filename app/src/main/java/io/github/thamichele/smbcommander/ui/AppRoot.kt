package io.github.thamichele.smbcommander.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.ui.theme.Arancio
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun AppRoot(vm: AppViewModel, onExit: () -> Unit) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val prompt by vm.syncPrompt.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(vm) {
        vm.uiEvents.collect { event ->
            when (event) {
                is UiEvent.Message -> scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(event.text)
                }
                is UiEvent.OpenFile -> if (!openExternally(context, event.file, event.name, event.edit)) {
                    vm.openFailed(event.file)
                }
            }
        }
    }

    BackHandler { if (!vm.back()) onExit() }

    Scaffold(
        containerColor = Arancio.colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = screen) {
                Screen.Setup -> SetupScreen(vm)
                Screen.Shares -> FoldersScreen(vm)
                is Screen.Browser -> BrowserScreen(vm, s)
            }
            transfer?.let { t ->
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth()
                        .background(Arancio.colors.surfaceRaised, RoundedCornerShape(12.dp))
                        .border(1.dp, Arancio.colors.border, RoundedCornerShape(12.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(t.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val fraction = t.fraction
                    if (fraction != null) {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                            color = Arancio.colors.accent,
                            trackColor = Arancio.colors.border,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Arancio.colors.accent,
                            trackColor = Arancio.colors.border,
                        )
                    }
                }
            }
        }
    }

    prompt?.let { p ->
        val name = RemotePath.name(p.session.path)
        AlertDialog(
            onDismissRequest = {},
            containerColor = Arancio.colors.surface,
            title = {
                Text(
                    if (p.remoteMissing) "«$name» non c'è più sul NAS" else "«$name» è cambiato anche sul NAS",
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Text(
                    if (p.remoteMissing) {
                        "Hai modificato il file sul telefono, ma nel frattempo è stato spostato o eliminato dal NAS."
                    } else {
                        "Hai modificato il file sul telefono, ma qualcun altro lo ha cambiato sul NAS dopo che l'hai aperto."
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = vm::resolveKeepBoth, shape = RoundedCornerShape(8.dp)) {
                        Text("Salva come copia")
                    }
                    OutlinedButton(onClick = vm::resolveOverwrite, shape = RoundedCornerShape(8.dp)) {
                        Text(if (p.remoteMissing) "Ricrea il file" else "Sovrascrivi il NAS")
                    }
                    TextButton(onClick = vm::resolveDiscard) {
                        Text("Scarta le modifiche", color = Arancio.colors.danger)
                    }
                }
            },
        )
    }
}

/** Hands the cached copy to another app; write permission lets editors save straight into it. */
private fun openExternally(context: Context, file: File, name: String, edit: Boolean): Boolean {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(RemotePath.extension(name)) ?: "*/*"
    val intent = Intent(if (edit) Intent.ACTION_EDIT else Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    return try {
        context.startActivity(if (edit) Intent.createChooser(intent, "Modifica con") else intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
