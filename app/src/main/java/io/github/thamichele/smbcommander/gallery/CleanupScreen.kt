/*
 * CleanupScreen.kt - the quick cleanup of the gallery: one card at a time, swipe to keep or discard
 *
 * Draws the cards, the tabs and the duplicates side by side; every decision is CleanupViewModel's.
 *
 * Version: 1.28.3
 */
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import io.github.thamichele.smbcommander.core.CleanupRules
import io.github.thamichele.smbcommander.core.PhotoKind
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.ImageSize
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.data.isVideo
import io.github.thamichele.smbcommander.ui.AppIcons
import io.github.thamichele.smbcommander.ui.AppViewModel
import io.github.thamichele.smbcommander.ui.ConfirmDialog
import io.github.thamichele.smbcommander.ui.DangerButton
import io.github.thamichele.smbcommander.ui.DeletionDialog
import io.github.thamichele.smbcommander.ui.EmptyState
import io.github.thamichele.smbcommander.ui.ErrorCard
import io.github.thamichele.smbcommander.ui.GhostButton
import io.github.thamichele.smbcommander.ui.InfoDialog
import io.github.thamichele.smbcommander.ui.Notice
import io.github.thamichele.smbcommander.ui.NoticeKind
import io.github.thamichele.smbcommander.ui.PageHeader
import io.github.thamichele.smbcommander.ui.Pill
import io.github.thamichele.smbcommander.ui.PrimaryButton
import io.github.thamichele.smbcommander.ui.SecondaryButton
import io.github.thamichele.smbcommander.ui.TOOL_BUTTON_SIZE
import io.github.thamichele.smbcommander.ui.ToolButton
import io.github.thamichele.smbcommander.ui.VideoBadge
import io.github.thamichele.smbcommander.ui.entryDetails
import io.github.thamichele.smbcommander.ui.rememberShimmer
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioType
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * CleanupScreen() - the quick cleanup: one card at a time, swipe left to discard, right to keep
 * @param appVm the app, for the deletion settings and the way back
 * @param gallery the gallery, whose photos are looked at
 * @param vm the cleanup's state and choices
 *
 * Only the card on top and the next one are drawn, and only the next few previews are fetched, so thousands of photos
 * cost nothing more than a few.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun CleanupScreen(appVm: AppViewModel, gallery: GalleryViewModel = viewModel(), vm: CleanupViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val galleryState by gallery.state.collectAsStateWithLifecycle()
    val deletion by vm.deletion.collectAsStateWithLifecycle()
    val sameContent by vm.sameContent.collectAsStateWithLifecycle()
    val c = Arancio.colors
    val context = LocalContext.current
    var trashDialog by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<RemoteEntry?>(null) }

    LaunchedEffect(galleryState.folder, galleryState.loading) {
        // once the gallery knows the folder's content; it is the phone's copy, so this is immediate
        if (!galleryState.loading || galleryState.months.isNotEmpty()) vm.start(galleryState.photos)
    }
    DisposableEffect(Unit) { onDispose { vm.flushNow() } }

    val queue = state.queue
    val top = queue.firstOrNull()
    val next = queue.getOrNull(1)

    // the next pairs of copies are fetched while the user compares this one
    val nextPairs = state.pairs.drop(1).take(2)
    LaunchedEffect(nextPairs) {
        val loader = SingletonImageLoader.get(context)
        nextPairs.forEach { pair ->
            listOf(pair.first, pair.second).forEach { loader.enqueue(ImageRequest.Builder(context).data(Thumb(it, ImageSize.Card)).build()) }
        }
    }

    // the next previews are fetched while the user looks at this one
    LaunchedEffect(top?.entry) {
        val loader = SingletonImageLoader.get(context)
        queue.drop(1).take(6).forEachIndexed { i, card ->
            loader.enqueue(ImageRequest.Builder(context).data(Thumb(card.entry)).build())
            if (i < 2) loader.enqueue(ImageRequest.Builder(context).data(Thumb(card.entry, ImageSize.Card)).build())
        }
    }

    Column(Modifier.fillMaxSize()) {
        PageHeader(
            context = "${state.keptCount} tenute · ${state.discardedCount} scartate in questa sessione",
            title = "Pulizia rapida",
            navigation = { ToolButton(AppIcons.Back, "Indietro", { appVm.back() }) },
        ) {
            if (top != null) ToolButton(AppIcons.Info, "Informazioni", { info = top.entry })
            ToolButton(AppIcons.Delete, "Cestino (${state.trashCount})", { trashDialog = true }, tint = if (state.trashCount > 0) c.danger else c.accentIcon)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            item { TabPill("Da verificare (${state.suspectsLeft})", state.tab == CleanupTab.Suspects) { vm.showTab(CleanupTab.Suspects) } }
            item { TabPill("Duplicati (${state.pairs.size})", state.tab == CleanupTab.Duplicates) { vm.showTab(CleanupTab.Duplicates) } }
            item { TabPill("Tutte le altre (${state.othersLeft})", state.tab == CleanupTab.Others) { vm.showTab(CleanupTab.Others) } }
        }
        if (state.tab == CleanupTab.Others && !state.loading) {
            // the groups of "all the others", filled while the photos are looked at on the phone
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = if (state.sorting > 0) 4.dp else 12.dp),
            ) {
                item { TabPill("Tutte", state.othersKind == null, small = true) { vm.showKind(null) } }
                for (kind in PhotoKind.entries) {
                    item(kind.name) {
                        TabPill("${kind.label} (${state.othersByKind[kind] ?: 0})", state.othersKind == kind, small = true) { vm.showKind(kind) }
                    }
                }
            }
            if (state.sorting > 0) {
                Text(
                    "Divido le foto per tipo sul telefono: ne mancano ${state.sorting}",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        }
        state.error?.let { error ->
            Notice(NoticeKind.Error, error, modifier = Modifier.padding(horizontal = 16.dp).clickable { vm.clearError() })
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            when {
                state.folder == null && galleryState.folder == null ->
                    ErrorCard("Scegli prima la cartella del backup", "Vai al backup foto", appVm::openBackup)
                state.loading || (galleryState.loading && galleryState.months.isEmpty()) ->
                    Box(Modifier.fillMaxSize().background(rememberShimmer(), ArancioShape.card))
                state.tab == CleanupTab.Duplicates -> {
                    val pair = state.pairs.firstOrNull()
                    if (pair == null) {
                        Done(
                            title = "Nessun duplicato",
                            text = "Non ci sono foto o video con lo stesso nome da confrontare.",
                            othersLeft = false,
                            onOthers = {},
                            onReload = { vm.reload(galleryState.photos) },
                        )
                    } else {
                        key(pair.key) {
                            DuplicatesPane(
                                pair = pair,
                                root = state.folder?.path.orEmpty(),
                                same = sameContent[pair.key],
                                onCompare = { vm.compare(pair) },
                                onKeep = { vm.keepOnly(pair, it) },
                                onOpen = { appVm.openViewer(listOf(pair.first, pair.second), if (it == pair.first) 0 else 1) },
                            )
                        }
                    }
                }
                top == null && state.tab == CleanupTab.Others && state.othersKind != null -> Done(
                    title = "Niente in «${state.othersKind?.label}»",
                    text = if (state.sorting > 0) "Sto ancora dividendo le foto per tipo: quelle trovate compariranno qui man mano."
                    else "In questo gruppo hai visto tutto. Il riconoscimento è approssimativo: qualche foto può essere finita in un altro gruppo.",
                    othersLeft = state.othersLeft > 0,
                    othersLabel = "Mostra tutte le altre",
                    onOthers = { vm.showKind(null) },
                    onReload = { vm.reload(galleryState.photos) },
                )
                top == null -> Done(
                    othersLeft = state.tab == CleanupTab.Suspects && state.othersLeft > 0,
                    onOthers = { vm.showTab(CleanupTab.Others) },
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
            if (state.tab == CleanupTab.Duplicates) {
                val pair = state.pairs.firstOrNull()
                RoundButton(AppIcons.Undo, "Annulla", c.textSecondary, enabled = state.canUndo, big = false) { vm.undo() }
                RoundButton(AppIcons.Check, "Tienili entrambi", c.accent, enabled = pair != null, big = false) { pair?.let(vm::keepBoth) }
            } else {
                RoundButton(AppIcons.Close, "Elimina", c.danger, enabled = top != null, big = true) { top?.let(vm::discard) }
                RoundButton(AppIcons.Undo, "Annulla", c.textSecondary, enabled = state.canUndo, big = false) { vm.undo() }
                RoundButton(AppIcons.Check, "Tieni", c.accent, enabled = top != null, big = true) { top?.let(vm::keep) }
            }
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
                        .border(2.dp, if (keep) c.accent else c.danger, ArancioShape.control)
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
                CardPicture(entry)
            } else {
                AsyncImage(Thumb(entry), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
            if (entry.isVideo) VideoBadge(Modifier.align(Alignment.Center), size = 56.dp)
            card.reason?.let { reason ->
                Text(
                    reason.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = c.text,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .background(c.surfaceRaised.copy(alpha = 0.9f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            Text(
                entryDetails(entry) + " · " + CleanupRules.relative(entry.path, root).substringBeforeLast('/', "").ifEmpty { "cartella del backup" },
                style = ArancioType.data,
                color = c.textSecondary,
            )
        }
    }
}

@Composable
private fun Done(
    othersLeft: Boolean,
    onOthers: () -> Unit,
    onReload: () -> Unit,
    title: String = "Niente da verificare",
    text: String? = null,
    othersLabel: String = "Rivedi anche le altre",
) {
    val c = Arancio.colors
    EmptyState(
        icon = AppIcons.Broom,
        title = title,
        text = text ?: if (othersLeft) "Le foto che sembravano da controllare le hai viste tutte. Puoi passare anche alle altre."
        else "Hai visto tutto. Le foto nuove del backup compariranno qui quando serve.",
    ) {
        if (othersLeft) PrimaryButton(onClick = onOthers) { Text(othersLabel) }
        else SecondaryButton(onClick = onReload) { Text("Ricarica") }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, label: String, tint: Color, enabled: Boolean, big: Boolean, onClick: () -> Unit) {
    val c = Arancio.colors
    val size = if (big) 64.dp else TOOL_BUTTON_SIZE
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(c.surfaceRaised)
                .border(1.dp, if (enabled) tint.copy(alpha = 0.6f) else c.borderStrong, CircleShape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .alpha(if (enabled) 1f else 0.5f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (enabled) tint else c.textSecondary, modifier = Modifier.size(size / 2.2f))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
    }
}

@Composable
private fun TabPill(text: String, active: Boolean, small: Boolean = false, onClick: () -> Unit) {
    val c = Arancio.colors
    if (!small) {
        // the main tabs: the filter chips of the kit
        Pill(text, active, onClick)
        return
    }
    // the groups inside a tab: quieter, the active one on the main colour at 10%
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (active) c.accentText else c.textSecondary,
        modifier = Modifier
            .clip(ArancioShape.control)
            .background(if (active) c.accentTint else Color.Transparent)
            .border(1.dp, if (active) c.accentBorder else c.border, ArancioShape.control)
            .selectable(selected = active, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun TrashDialog(count: Int, size: String, onDismiss: () -> Unit, onEmpty: () -> Unit, onRestore: () -> Unit) {
    val c = Arancio.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceRaised,
        title = { Text("Cestino: ${if (count == 1) "1 elemento" else "$count elementi"}, $size", style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                "Svuotandolo le foto vengono eliminate dal NAS per sempre. Il backup non le ricaricherà comunque, " +
                    "perché sono già nell'elenco delle foto eliminate.",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            DangerButton(
                onClick = onEmpty,
            ) { Text("Svuota") }
        },
        dismissButton = {
            Row {
                GhostButton(onClick = onRestore) { Text("Ripristina tutto") }
                GhostButton(onClick = onDismiss) { Text("Chiudi") }
            }
        },
    )
}

/**
 * Two copies of the same photo side by side, each with its full path under it: the one chosen stays, the other goes
 * to the bin. Over them, whether the two files are the very same bytes.
 */
@Composable
private fun DuplicatesPane(
    pair: DuplicatePair,
    root: String,
    same: Boolean?,
    onCompare: () -> Unit,
    onKeep: (RemoteEntry) -> Unit,
    onOpen: (RemoteEntry) -> Unit,
) {
    val c = Arancio.colors
    LaunchedEffect(pair.key) { onCompare() }
    val verdict = when {
        same == true -> Verdict.Same
        same == false || pair.first.size != pair.second.size -> Verdict.Different
        pair.first.size > MAX_COMPARE_BYTES -> Verdict.Unknown
        else -> Verdict.Checking
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Scegli quale tenere", style = MaterialTheme.typography.titleMedium)
        VerdictBanner(verdict, sameSize = pair.first.size == pair.second.size)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (entry in listOf(pair.first, pair.second)) {
                DuplicateSide(entry, root, verdict, Modifier.weight(1f), onKeep = { onKeep(entry) }, onOpen = { onOpen(entry) })
            }
        }
    }
}

/** What the comparison of two copies said: drives the banner over them and the colour of their outline. */
private enum class Verdict { Same, Different, Checking, Unknown }

/** The verdict in large type and colour, so "identical" and "different" are clear at a glance. */
@Composable
private fun VerdictBanner(verdict: Verdict, sameSize: Boolean) {
    val c = Arancio.colors
    val (tint, icon, title, detail) = when (verdict) {
        Verdict.Same -> Banner(c.success, AppIcons.Check, "Sono identiche", "Stessi byte: puoi tenerne una sola senza perdere nulla")
        Verdict.Different -> Banner(
            c.danger, AppIcons.Alert, "Sono diverse",
            if (sameSize) "Stessa dimensione ma contenuto diverso: guardale prima di scegliere"
            else "Dimensioni diverse: una delle due è stata modificata o ricompressa",
        )
        Verdict.Unknown -> Banner(c.textSecondary, AppIcons.Info, "Non confrontate", "File troppo grandi per confrontarli dalla rete: guardale tu")
        Verdict.Checking -> Banner(c.textSecondary, null, "Confronto il contenuto…", "Stessa dimensione: controllo se sono gli stessi byte")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(tint.copy(alpha = 0.16f))
            .border(1.5.dp, tint, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
        } else {
            CircularProgressIndicator(color = tint, strokeWidth = 2.5.dp, modifier = Modifier.size(24.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tint)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.text)
        }
    }
}

private data class Banner(val tint: Color, val icon: ImageVector?, val title: String, val detail: String)

@Composable
private fun DuplicateSide(entry: RemoteEntry, root: String, verdict: Verdict, modifier: Modifier, onKeep: () -> Unit, onOpen: () -> Unit) {
    val c = Arancio.colors
    // identical copies get a thick green outline, different ones a red one: the answer shows on the photos themselves
    val (outline, width) = when (verdict) {
        Verdict.Same -> c.success to 3.dp
        Verdict.Different -> c.danger to 2.dp
        else -> c.border to 1.dp
    }
    val folder = CleanupRules.relative(entry.path, root).substringBeforeLast('/', "").ifEmpty { "cartella del backup" }
    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .border(width, outline, RoundedCornerShape(12.dp)),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black).clickable(onClick = onOpen)) {
            CardPicture(entry)
            if (entry.isVideo) VideoBadge(Modifier.align(Alignment.Center), size = 40.dp)
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleSmall)
            Text(folder, style = ArancioType.data, color = c.text)
            Text(entryDetails(entry), style = ArancioType.data, color = c.textSecondary)
        }
        PrimaryButton(
            onClick = onKeep,
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
        ) { Text("Tieni questo") }
    }
}

/* The big picture of a card: the sharp copy, with the thumbnail shown while it loads or when it cannot be made. */
@Composable
private fun CardPicture(entry: RemoteEntry) {
    SubcomposeAsyncImage(
        model = Thumb(entry, ImageSize.Card),
        contentDescription = entry.name,
        contentScale = ContentScale.Fit,
        loading = { AsyncImage(Thumb(entry), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) },
        error = { AsyncImage(Thumb(entry), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) },
        modifier = Modifier.fillMaxSize(),
    )
}
