package io.github.thamichele.smbcommander.gallery

import android.text.format.Formatter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import io.github.thamichele.smbcommander.core.CleanupRules
import io.github.thamichele.smbcommander.data.ImageSize
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.data.isVideo
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.AppViewModel
import io.github.thamichele.smbcommander.ui.ConfirmDialog
import io.github.thamichele.smbcommander.ui.DeletionDialog
import io.github.thamichele.smbcommander.ui.ErrorCard
import io.github.thamichele.smbcommander.ui.InfoDialog
import io.github.thamichele.smbcommander.ui.PageHeader
import io.github.thamichele.smbcommander.ui.ToolButton
import io.github.thamichele.smbcommander.ui.VideoBadge
import io.github.thamichele.smbcommander.ui.entryDetails
import io.github.thamichele.smbcommander.ui.theme.Arancio
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * One card at a time: swipe left (or ✕) to send the photo to the bin, right (or ✓) to keep it. Only the card on top
 * and the next one are drawn, and only the next few previews are fetched, so thousands of photos cost nothing more
 * than a few.
 */
@Composable
fun CleanupScreen(appVm: AppViewModel, gallery: GalleryViewModel = viewModel(), vm: CleanupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val galleryState by gallery.state.collectAsStateWithLifecycle()
    val deletion by vm.deletion.collectAsStateWithLifecycle()
    val c = Arancio.colors
    val context = LocalContext.current
    var trashDialog by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<io.github.thamichele.smbcommander.core.RemoteEntry?>(null) }

    LaunchedEffect(galleryState.folder, galleryState.loading) {
        // once the gallery knows the folder's content; it is the phone's copy, so this is immediate
        if (!galleryState.loading || galleryState.months.isNotEmpty()) vm.start(galleryState.photos)
    }
    DisposableEffect(Unit) { onDispose { vm.flushNow() } }

    val queue = state.queue
    val top = queue.firstOrNull()
    val next = queue.getOrNull(1)

    // the next previews are fetched while the user looks at this one
    LaunchedEffect(top?.entry) {
        val loader = SingletonImageLoader.get(context)
        queue.drop(1).take(6).forEachIndexed { i, card ->
            loader.enqueue(ImageRequest.Builder(context).data(Thumb(card.entry)).build())
            if (i < 2) loader.enqueue(ImageRequest.Builder(context).data(Thumb(card.entry, ImageSize.Preview)).build())
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            context = "${state.keptCount} tenute · ${state.discardedCount} scartate in questa sessione",
            title = "Pulizia rapida",
            navigation = { ToolButton(AppIcons.Back, "Indietro", { appVm.back() }) },
        ) {
            if (top != null) ToolButton(AppIcons.Info, "Informazioni", { info = top.entry })
            ToolButton(AppIcons.Delete, "Cestino (${state.trashCount})", { trashDialog = true }, tint = if (state.trashCount > 0) c.danger else c.text)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            item { Pill("Da verificare (${state.suspectsLeft})", !state.showingOthers) { vm.showOthers(false) } }
            item { Pill("Tutte le altre (${state.othersLeft})", state.showingOthers) { vm.showOthers(true) } }
        }
        state.error?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = c.danger,
                modifier = Modifier.padding(horizontal = 16.dp).clickable { vm.clearError() },
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            when {
                state.folder == null && galleryState.folder == null ->
                    ErrorCard("Scegli prima la cartella del backup", "Vai al backup foto", appVm::openBackup)
                state.loading || (galleryState.loading && galleryState.months.isEmpty()) ->
                    CircularProgressIndicator(color = c.accent)
                top == null -> Done(
                    othersLeft = !state.showingOthers && state.othersLeft > 0,
                    onOthers = { vm.showOthers(true) },
                    onReload = { vm.reload(galleryState.photos) },
                )
                else -> {
                    if (next != null) key(next.entry.path) { PhotoCard(next, root = state.folder?.path.orEmpty(), behind = true) }
                    key(top.entry.path) {
                        SwipeCard(
                            onKeep = { vm.keep(top) },
                            onDiscard = { vm.discard(top) },
                        ) { PhotoCard(top, root = state.folder?.path.orEmpty(), behind = false, onOpen = { appVm.openViewer(listOf(top.entry), 0) }) }
                    }
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundButton(AppIcons.Close, "Elimina", c.danger, enabled = top != null, big = true) { top?.let(vm::discard) }
            RoundButton(AppIcons.Undo, "Annulla", c.textSecondary, enabled = state.canUndo, big = false) { vm.undo() }
            RoundButton(AppIcons.Check, "Tieni", c.accent, enabled = top != null, big = true) { top?.let(vm::keep) }
        }
    }

    info?.let { InfoDialog(it, onDismiss = { info = null }) }

    deletion?.let { DeletionDialog(it, onStop = vm::stopDeletion, onClose = vm::closeDeletion) }
    if (trashDialog) {
        val size = Formatter.formatShortFileSize(context, state.trashBytes)
        if (state.trashCount == 0) {
            ConfirmDialog(
                title = "Il cestino è vuoto",
                text = "Le foto scartate finiscono qui, in una cartella nascosta dentro la cartella del backup, finché non lo svuoti.",
                confirm = "Ok",
                danger = false,
                onDismiss = { trashDialog = false },
                onConfirm = { trashDialog = false },
            )
        } else {
            TrashDialog(
                count = state.trashCount,
                size = size,
                onDismiss = { trashDialog = false },
                onEmpty = { trashDialog = false; vm.emptyTrash() },
                onRestore = { trashDialog = false; vm.restoreTrash() },
            )
        }
    }
}

/** The card on top, following the finger; past a third of the width it flies off and the choice is made. */
@Composable
private fun SwipeCard(onKeep: () -> Unit, onDiscard: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val c = Arancio.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = with(LocalDensity.current) { maxWidth.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = offset.value
                    rotationZ = offset.value / width * 12f
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when {
                                    offset.value > width / 3 -> {
                                        offset.animateTo(width * 1.5f, tween(180))
                                        onKeep()
                                    }
                                    offset.value < -width / 3 -> {
                                        offset.animateTo(-width * 1.5f, tween(180))
                                        onDiscard()
                                    }
                                    else -> offset.animateTo(0f)
                                }
                            }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    ) { change, drag ->
                        change.consume()
                        scope.launch { offset.snapTo(offset.value + drag.x) }
                    }
                },
        ) {
            content()
            val progress = (abs(offset.value) / (width / 3)).coerceIn(0f, 1f)
            if (progress > 0.05f) {
                val keep = offset.value > 0
                Text(
                    if (keep) "TIENI" else "ELIMINA",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (keep) c.accent else c.danger,
                    modifier = Modifier
                        .align(if (keep) Alignment.TopStart else Alignment.TopEnd)
                        .padding(24.dp)
                        .graphicsLayer { alpha = progress; rotationZ = if (keep) -12f else 12f }
                        .border(2.dp, if (keep) c.accent else c.danger, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun PhotoCard(card: CleanupCard, root: String, behind: Boolean, onOpen: () -> Unit = {}) {
    val c = Arancio.colors
    val entry = card.entry
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { if (behind) { scaleX = 0.94f; scaleY = 0.94f; alpha = 0.6f } }
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(1.dp, c.border, RoundedCornerShape(12.dp)),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).clickable(enabled = !behind, onClick = onOpen)) {
            if (!behind) {
                SubcomposeAsyncImage(
                    model = Thumb(entry, ImageSize.Preview),
                    contentDescription = entry.name,
                    contentScale = ContentScale.Fit,
                    loading = { AsyncImage(Thumb(entry), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) },
                    error = { AsyncImage(Thumb(entry), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(Thumb(entry), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            if (entry.isVideo) VideoBadge(Modifier.align(Alignment.Center), size = 56.dp)
            card.reason?.let { reason ->
                Text(
                    reason.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(c.surfaceRaised.copy(alpha = 0.9f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                entryDetails(entry) + " · " + CleanupRules.relative(entry.path, root).substringBeforeLast('/', "").ifEmpty { "cartella del backup" },
                style = MaterialTheme.typography.bodySmall,
                color = c.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Done(othersLeft: Boolean, onOthers: () -> Unit, onReload: () -> Unit) {
    val c = Arancio.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(AppIcons.Broom, contentDescription = null, tint = c.accent, modifier = Modifier.size(48.dp))
        Text("Niente da verificare", style = MaterialTheme.typography.titleLarge)
        Text(
            if (othersLeft) "Le foto che sembravano da controllare le hai viste tutte. Puoi passare anche alle altre."
            else "Hai visto tutto. Le foto nuove del backup compariranno qui quando serve.",
            style = MaterialTheme.typography.bodyLarge,
            color = c.textSecondary,
        )
        if (othersLeft) Button(onClick = onOthers, shape = RoundedCornerShape(8.dp)) { Text("Rivedi anche le altre") }
        else Button(onClick = onReload, shape = RoundedCornerShape(8.dp)) { Text("Ricarica") }
    }
}

@Composable
private fun RoundButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, enabled: Boolean, big: Boolean, onClick: () -> Unit) {
    val c = Arancio.colors
    val size = if (big) 64.dp else 48.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(c.surfaceRaised)
                .border(1.dp, if (enabled) tint else c.border, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else c.border, modifier = Modifier.size(size / 2.2f))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    }
}

@Composable
private fun Pill(text: String, active: Boolean, onClick: () -> Unit) {
    val c = Arancio.colors
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (active) c.text else c.textSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) c.accentSoft else c.surface)
            .border(1.dp, if (active) c.accent else c.border, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun TrashDialog(count: Int, size: String, onDismiss: () -> Unit, onEmpty: () -> Unit, onRestore: () -> Unit) {
    val c = Arancio.colors
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface,
        title = { Text("Cestino: ${if (count == 1) "1 elemento" else "$count elementi"}, $size", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                "Svuotandolo le foto vengono eliminate dal NAS per sempre. Il backup non le ricaricherà comunque, " +
                    "perché sono già nell'elenco delle foto eliminate.",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            Button(
                onClick = onEmpty,
                shape = RoundedCornerShape(8.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = c.danger, contentColor = c.onAccent),
            ) { Text("Svuota") }
        },
        dismissButton = {
            Row {
                androidx.compose.material3.TextButton(onClick = onRestore) { Text("Ripristina tutto") }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Chiudi") }
            }
        },
    )
}
