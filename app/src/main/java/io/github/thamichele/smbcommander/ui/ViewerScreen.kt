package io.github.thamichele.smbcommander.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.ImageSize
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.ui.theme.Arancio

/**
 * Photos of a folder, one per page. Each shows the light preview cached on the phone (1600 px), with the thumbnail
 * as placeholder while it is made; the original is opened only on request.
 */
@Composable
fun ViewerScreen(vm: AppViewModel, screen: Screen.Viewer) {
    if (screen.images.isEmpty()) return
    val pager = rememberPagerState(initialPage = screen.index) { screen.images.size }
    var chrome by remember { mutableStateOf(true) }
    var pendingDownload by remember { mutableStateOf<RemoteEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<RemoteEntry?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) vm.downloadTo(entry, uri)
    }
    val current = screen.images[pager.currentPage.coerceIn(0, screen.images.lastIndex)]

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            ZoomableImage(screen.images[page], onTap = { chrome = !chrome })
        }

        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(AppIcons.Back, "Indietro", { vm.back() }, tint = Color.White)
                Column(Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        current.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${pager.currentPage + 1} di ${screen.images.size} · ${entryDetails(current)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
                ToolButton(AppIcons.OpenWith, "Apri l'originale", { vm.open(current, edit = false) }, tint = Color.White)
                ToolButton(
                    AppIcons.Download,
                    "Salva l'originale sul telefono",
                    { pendingDownload = current; saveFile.launch(current.name) },
                    tint = Color.White,
                )
                ToolButton(AppIcons.Delete, "Elimina dal NAS", { confirmDelete = current }, tint = Color.White)
            }
        }
    }

    confirmDelete?.let { photo ->
        ConfirmDialog(
            title = "Eliminare «${photo.name}»?",
            text = "La foto verrà eliminata dal NAS. Se era sul telefono, lì resta e il backup non la caricherà di nuovo.",
            confirm = "Elimina",
            danger = true,
            onDismiss = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                vm.deletePhotos(listOf(photo))
            },
        )
    }
}

@Composable
private fun ZoomableImage(entry: RemoteEntry, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(entry) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            // panning only while zoomed in: at normal size horizontal drags turn the page
            .transformable(state, canPan = { scale > 1f }),
        contentAlignment = Alignment.Center,
    ) {
        val zoomed = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y
        }
        SubcomposeAsyncImage(
            model = Thumb(entry, ImageSize.Preview),
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            loading = {
                AsyncImage(
                    model = Thumb(entry, ImageSize.Thumb),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            },
            error = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Anteprima non disponibile",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Arancio.colors.textSecondary,
                    )
                }
            },
            modifier = zoomed,
        )
    }
}
