package io.github.thamichele.smbcommander.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.thamichele.smbcommander.backup.BackupScreen
import io.github.thamichele.smbcommander.gallery.GalleryScreen
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
        // in the bar, not in the content, so snackbars appear above it
        bottomBar = {
            if (screen == Screen.Shares || screen == Screen.Gallery || screen == Screen.Backup) {
                BottomNav(screen, vm)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = screen) {
                Screen.Setup -> SetupScreen(vm)
                Screen.Shares -> FoldersScreen(vm)
                Screen.Gallery -> GalleryScreen(vm)
                Screen.Backup -> BackupScreen()
                is Screen.Browser -> BrowserScreen(vm, s)
                is Screen.Viewer -> ViewerScreen(vm, s)
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

/** The sections of the app, as in the kit's bottom bar: pill behind the active icon. */
@Composable
private fun BottomNav(screen: Screen, vm: AppViewModel) {
    val c = Arancio.colors
    Column(Modifier.background(c.surface).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp)) {
            NavItem(AppIcons.Folder, "File", screen == Screen.Shares, vm::openFiles, Modifier.weight(1f))
            NavItem(AppIcons.Image, "Galleria", screen == Screen.Gallery, vm::openGallery, Modifier.weight(1f))
            NavItem(AppIcons.Upload, "Backup foto", screen == Screen.Backup, vm::openBackup, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val c = Arancio.colors
    Column(
        modifier.clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .width(56.dp)
                .height(30.dp)
                .background(if (active) c.accentSoft else Color.Transparent, RoundedCornerShape(999.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = if (active) c.text else c.textSecondary, modifier = Modifier.size(22.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) c.text else c.textSecondary,
        )
    }
}

/** Hands the cached copy to another app; only editors get write permission, to save straight into it. */
private fun openExternally(context: Context, file: File, name: String, edit: Boolean): Boolean {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(RemotePath.extension(name)) ?: "*/*"
    // write access also when just opening: most editors are reached through "open" (VIEW), and the copy is a private,
    // per-session file of our cache that is checked for changes before anything goes back to the NAS
    val intent = Intent(if (edit) Intent.ACTION_EDIT else Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .setClipData(ClipData.newRawUri(name, uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    return try {
        context.startActivity(if (edit) Intent.createChooser(intent, "Modifica con") else intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}
