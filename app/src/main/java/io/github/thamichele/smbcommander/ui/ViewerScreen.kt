package io.github.thamichele.smbcommander.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.ImageSize
import io.github.thamichele.smbcommander.data.SmbDataSource
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.data.isVideo
import io.github.thamichele.smbcommander.ui.theme.Arancio

/**
 * Photos and videos of a folder, one per page. A photo shows the full-size copy cached on the phone (same pixels as the
 * original, JPEG compressed), with the thumbnail as placeholder while it is made; a video streams from the NAS when
 * played. The original is opened only on request.
 */
@Composable
fun ViewerScreen(vm: AppViewModel, screen: Screen.Viewer) {
    if (screen.images.isEmpty()) return
    val pager = rememberPagerState(initialPage = screen.index) { screen.images.size }
    var chrome by remember { mutableStateOf(true) }
    var pendingDownload by remember { mutableStateOf<RemoteEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<RemoteEntry?>(null) }
    var info by remember { mutableStateOf<RemoteEntry?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = pendingDownload
        pendingDownload = null
        if (uri != null && entry != null) vm.downloadTo(entry, uri)
    }
    val current = screen.images[pager.currentPage.coerceIn(0, screen.images.lastIndex)]
    // the video playing now; only the current page plays, and turning the page stops it
    var playing by remember { mutableStateOf<RemoteEntry?>(null) }
    LaunchedEffect(pager.currentPage) { playing = null }
    BackHandler(enabled = playing != null) { playing = null }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // while a video plays its own controls take the touches: back (or the arrow) stops it and frees the swipe
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            userScrollEnabled = playing == null,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val entry = screen.images[page]
            when {
                !entry.isVideo -> ZoomableImage(entry, onTap = { chrome = !chrome })
                playing == entry -> VideoPlayer(entry, onEnded = { playing = null })
                else -> VideoPoster(entry, onPlay = { playing = entry }, onTap = { chrome = !chrome })
            }
        }

        AnimatedVisibility(visible = chrome, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(AppIcons.Back, "Indietro", { if (playing != null) playing = null else vm.back() }, tint = Color.White)
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
                ToolButton(AppIcons.Info, "Informazioni", { info = current }, tint = Color.White)
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

    info?.let { InfoDialog(it, onDismiss = { info = null }) }

    confirmDelete?.let { photo ->
        ConfirmDialog(
            title = "Eliminare «${photo.name}»?",
            text = "${if (photo.isVideo) "Il video" else "La foto"} verrà eliminato dal NAS. Se era sul telefono, lì resta e il backup non lo caricherà di nuovo.",
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
        val context = LocalPlatformContext.current
        // decoded at its own size, not the screen's, so zooming in shows the real detail
        val full = remember(entry) {
            ImageRequest.Builder(context).data(Thumb(entry, ImageSize.Preview)).size(Size.ORIGINAL).build()
        }
        SubcomposeAsyncImage(
            model = full,
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

/** The first frame of a video with a play button; the video itself streams from the NAS only when asked. */
@Composable
private fun VideoPoster(entry: RemoteEntry, onPlay: () -> Unit, onTap: () -> Unit) {
    Box(
        Modifier.fillMaxSize().pointerInput(entry) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = Thumb(entry, ImageSize.Preview),
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(AppIcons.Play, contentDescription = "Riproduci", tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPlayer(entry: RemoteEntry, onEnded: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as SmbCommanderApp
    val ended by rememberUpdatedState(onEnded)
    val player = remember(entry) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory(app.repository)))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(SmbDataSource.uri(entry.share, entry.path)))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = { PlayerView(it).apply { this.player = player } },
        modifier = Modifier.fillMaxSize(),
    )
}
