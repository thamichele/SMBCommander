package io.github.thamichele.smbcommander.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.thamichele.smbcommander.core.RemotePath
import io.github.thamichele.smbcommander.gallery.CleanupScreen
import io.github.thamichele.smbcommander.gallery.GalleryScreen
import io.github.thamichele.smbcommander.share.ShareActivity
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun AppRoot(vm: AppViewModel, onExit: () -> Unit) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val transfer by vm.transfer.collectAsStateWithLifecycle()
    val prompt by vm.syncPrompt.collectAsStateWithLifecycle()
    val jobs by vm.transfers.collectAsStateWithLifecycle()
    val transfersPaused by vm.transfersPaused.collectAsStateWithLifecycle()
    val conflictAsk by vm.conflictAsk.collectAsStateWithLifecycle()
    val music by vm.music.collectAsStateWithLifecycle()
    val deletion by vm.deletion.collectAsStateWithLifecycle()
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
                is UiEvent.MessageWithAction -> scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar(event.text, actionLabel = event.action, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                        event.onAction()
                    }
                }
                is UiEvent.OpenFile -> if (!openExternally(context, event.file, event.name, event.edit)) {
                    vm.openFailed(event.file)
                }
                is UiEvent.Share -> if (!shareExternally(context, event.files)) {
                    event.files.firstOrNull()?.parentFile?.deleteRecursively()
                    snackbar.currentSnackbarData?.dismiss()
                    scope.launch { snackbar.showSnackbar("Nessuna app installata può ricevere questi file") }
                }
            }
        }
    }

    BackHandler { if (!vm.back()) onExit() }

    Scaffold(
        containerColor = Arancio.colors.background,
        snackbarHost = { ArancioSnackbarHost(snackbar) },
        // in the bar, not in the content, so snackbars appear above it
        bottomBar = {
            if (screen == Screen.Shares || screen == Screen.Gallery || screen == Screen.Backup) {
                BottomNav(screen, vm)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            // the viewer opens over the screen it came from, which stays composed: back returns to the same scroll
            // position in the gallery or the folder
            val viewer = screen as? Screen.Viewer
            when (val s = viewer?.returnTo ?: screen) {
                Screen.Setup -> SetupScreen(vm)
                Screen.Shares -> FoldersScreen(vm)
                Screen.Gallery -> GalleryScreen(vm)
                Screen.Cleanup -> CleanupScreen(vm)
                is Screen.Trash -> TrashScreen(vm)
                is Screen.Archive -> ArchiveScreen(vm, s)
                Screen.Backup -> SettingsScreen(vm)
                is Screen.Browser -> BrowserScreen(vm, s)
                is Screen.Viewer -> ViewerScreen(vm, s)
            }
            if (viewer != null) ViewerScreen(vm, viewer)
            // the song playing stays on every screen but the full-screen viewer, above the transfers
            if (music.active && viewer == null) {
                MiniPlayer(
                    music,
                    onToggle = vm::musicToggle,
                    onNext = vm::musicNext,
                    onPrevious = vm::musicPrevious,
                    onSeek = vm::musicSeek,
                    onClose = vm::musicStop,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = if (jobs.isNotEmpty() || transfer != null) 120.dp else 0.dp),
                )
            }
            if (jobs.isNotEmpty() && transfer == null) {
                TransferCard(
                    jobs,
                    paused = transfersPaused,
                    onCancel = vm::cancelTransfers,
                    onPause = vm::pauseTransfers,
                    onResume = vm::resumeTransfers,
                    onRetry = vm::retryTransfer,
                    onClose = vm::clearTransfers,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            conflictAsk?.let { ConflictDialog(it, onChoice = vm::answerConflict) }
            deletion?.let { DeletionDialog(it, onStop = vm::stopDeletion, onClose = vm::closeDeletion) }
            transfer?.let { t ->
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                        .fillMaxWidth()
                        .floating(Arancio.colors)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(t.label, style = MaterialTheme.typography.titleSmall)
                    ArancioProgress(t.fraction)
                }
            }
        }
    }

    prompt?.let { p ->
        val name = RemotePath.name(p.session.path)
        AlertDialog(
            onDismissRequest = {},
            containerColor = Arancio.colors.surfaceRaised,
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
                    PrimaryButton(onClick = vm::resolveKeepBoth) {
                        Text("Salva come copia")
                    }
                    SecondaryButton(onClick = vm::resolveOverwrite) {
                        Text(if (p.remoteMissing) "Ricrea il file" else "Sovrascrivi il NAS")
                    }
                    GhostButton(onClick = vm::resolveDiscard) {
                        Text("Scarta le modifiche", color = Arancio.colors.danger)
                    }
                }
            },
        )
    }
}

/** Pill behind the icon of the active section, and the room around the items: a low bar with little empty space. */
private val NAV_PILL_WIDTH = 52.dp
private val NAV_PILL_HEIGHT = 28.dp
private val NAV_ICON_SIZE = 22.dp
private val NAV_BAR_PADDING = 4.dp
private val NAV_ITEM_PADDING = 2.dp

/** The pill slides to the section touched: its front edge leads, its back edge follows a little later and catches up. */
private const val NAV_LEAD_MS = 200
private const val NAV_TRAIL_MS = 320
private const val NAV_ACTIVE_SCALE = 1.1f
private val NavEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/* Private: remembers the section the pill was on, to know which of its edges leads. */
private class NavMotion(private var section: Int) {
    var isMovingRight = true
        private set

    fun moveTo(target: Int) {
        if (target == section) return
        isMovingRight = target > section
        section = target
    }
}

private class NavSection(val icon: ImageVector, val label: String, val screen: Screen, val open: () -> Unit)

/** The sections of the app, as in the kit's bottom bar: one pill that slides behind the active icon. */
@Composable
private fun BottomNav(screen: Screen, vm: AppViewModel) {
    val c = Arancio.colors
    val sections = listOf(
        NavSection(AppIcons.Folder, "File", Screen.Shares, vm::openFiles),
        NavSection(AppIcons.Image, "Galleria", Screen.Gallery, vm::openGallery),
        NavSection(AppIcons.Settings, "Impostazioni", Screen.Backup, vm::openSettings),
    )
    val active = sections.indexOfFirst { it.screen == screen }.coerceAtLeast(0)
    Column(Modifier.background(c.nav).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = NAV_BAR_PADDING)) {
            SlidingPill(active, maxWidth / sections.size)
            Row(Modifier.fillMaxWidth().selectableGroup()) {
                sections.forEachIndexed { index, section ->
                    NavItem(section, index == active, Modifier.weight(1f))
                }
            }
        }
    }
}

/* Private: the pill behind the active icon; itemWidth is the width of one section. */
@Composable
private fun SlidingPill(active: Int, itemWidth: Dp) {
    // which way the last change went; kept while the pill is still travelling
    val motion = remember { NavMotion(active) }
    motion.moveTo(active)
    val isMovingRight = motion.isMovingRight
    val left = itemWidth * active + (itemWidth - NAV_PILL_WIDTH) / 2
    val right = left + NAV_PILL_WIDTH
    // the edge on the side of the move goes first: the pill stretches, then gathers itself
    val leftEdge by animateDpAsState(left, tween(if (isMovingRight) NAV_TRAIL_MS else NAV_LEAD_MS, easing = NavEasing), label = "nav-left")
    val rightEdge by animateDpAsState(right, tween(if (isMovingRight) NAV_LEAD_MS else NAV_TRAIL_MS, easing = NavEasing), label = "nav-right")
    Box(
        Modifier
            .padding(top = NAV_ITEM_PADDING)
            .offset { IntOffset(leftEdge.roundToPx(), 0) }
            .width(rightEdge - leftEdge)
            .height(NAV_PILL_HEIGHT)
            .background(Arancio.colors.accentSoft, ArancioShape.pill),
    )
}

@Composable
private fun NavItem(section: NavSection, active: Boolean, modifier: Modifier) {
    val c = Arancio.colors
    val tint by animateColorAsState(if (active) c.text else c.textSecondary, tween(NAV_LEAD_MS), label = "nav-tint")
    val scale by animateFloatAsState(if (active) NAV_ACTIVE_SCALE else 1f, tween(NAV_TRAIL_MS, easing = NavEasing), label = "nav-scale")
    Column(
        modifier
            .heightIn(min = 48.dp)
            .clip(ArancioShape.control)
            .selectable(selected = active, role = Role.Tab, onClick = section.open)
            .padding(vertical = NAV_ITEM_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NAV_ITEM_PADDING),
    ) {
        Box(Modifier.height(NAV_PILL_HEIGHT), contentAlignment = Alignment.Center) {
            Icon(
                section.icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(NAV_ICON_SIZE).graphicsLayer { scaleX = scale; scaleY = scale },
            )
        }
        Text(section.label, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** Hands the cached copy to another app, which may save straight into it. */
private fun openExternally(context: Context, file: File, name: String, edit: Boolean): Boolean {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(RemotePath.extension(name)) ?: "*/*"
    // write access also when just opening: most editors are reached through "open" (VIEW), and the copy is a private,
    // per-session file of our cache that is checked for changes before anything goes back to the NAS
    val intent = Intent(if (edit) Intent.ACTION_EDIT else Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(name, uri) }
    return try {
        context.startActivity(if (edit) Intent.createChooser(intent, "Modifica con") else intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Opens the share sheet on the temporary copies, read-only, without our own "Salva sul NAS" among the targets. */
private fun shareExternally(context: Context, files: List<File>): Boolean {
    if (files.isEmpty()) return false
    val uris = files.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) }
    val mimes = files.map { MimeTypeMap.getSingleton().getMimeTypeFromExtension(RemotePath.extension(it.name)) ?: "*/*" }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).setType(mimes[0]).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(commonMime(mimes)).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri(files[0].name, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    val chooser = Intent.createChooser(intent, if (files.size == 1) "Condividi ${files[0].name}" else "Condividi ${files.size} file")
        .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, ShareActivity::class.java)))
    return try {
        context.startActivity(chooser)
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

// the same type for files all of one type, "image/" plus a star for photos of mixed kinds, else any type
private fun commonMime(mimes: List<String>): String {
    val first = mimes.first()
    if (mimes.all { it == first }) return first
    val kind = first.substringBefore('/')
    return if (kind != "*" && mimes.all { it.substringBefore('/') == kind }) "$kind/*" else "*/*"
}
