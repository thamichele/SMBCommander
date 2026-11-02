package io.github.thamichele.smbcommander.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
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
import kotlinx.coroutines.delay

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
    // full screen exists only while a video plays: system bars hidden, top bar hidden, landscape videos turn sideways
    var fullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(pager.currentPage) { playing = null }
    LaunchedEffect(playing) { if (playing == null) fullscreen = false }
    BackHandler(enabled = playing != null) { playing = null }
    // declared after the one above, so it wins: back first leaves full screen, then stops the video
    BackHandler(enabled = fullscreen) { fullscreen = false }

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
                playing == entry -> VideoPlayer(
                    entry,
                    fullscreen = fullscreen,
                    onFullscreenChange = { fullscreen = it },
                    onEnded = { playing = null },
                )
                else -> VideoPoster(entry, onPlay = { playing = entry }, onTap = { chrome = !chrome })
            }
        }

        AnimatedVisibility(visible = chrome && !fullscreen, enter = fadeIn(), exit = fadeOut()) {
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
                ToolButton(AppIcons.Share, "Condividi", { vm.share(listOf(current)) }, tint = Color.White)
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

/** How far a double tap jumps, back on the left side of the video and forward on the right side. */
private const val SEEK_STEP_MS = 10_000L

/** A double tap jumped: which way, how far in total (taps in a row add up), and a counter that restarts the label. */
private class SeekFeedback(val forward: Boolean, val seconds: Int, val id: Long)

@androidx.annotation.OptIn(UnstableApi::class)
@SuppressLint("ClickableViewAccessibility")
@Composable
private fun VideoPlayer(
    entry: RemoteEntry,
    fullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onEnded: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as SmbCommanderApp
    val ended by rememberUpdatedState(onEnded)
    val fullscreenChange by rememberUpdatedState(onFullscreenChange)
    val player = remember(entry) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory(app.repository)))
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(SmbDataSource.uri(entry.share, entry.path)))
                prepare()
                playWhenReady = true
            }
    }
    // wider than tall: full screen turns the phone sideways
    var landscape by remember(entry) { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var feedback by remember { mutableStateOf<SeekFeedback?>(null) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ended()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    landscape = videoSize.width * videoSize.pixelWidthHeightRatio > videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    val activity = remember(context) { context.findActivity() }
    if (fullscreen && activity != null) {
        DisposableEffect(activity, landscape) {
            val window = activity.window
            val bars = WindowCompat.getInsetsController(window, window.decorView)
            val orientation = activity.requestedOrientation
            bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            bars.hide(WindowInsetsCompat.Type.systemBars())
            if (landscape) activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            onDispose {
                bars.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = orientation
            }
        }
    }

    // a label in the middle of the side that was tapped, gone a moment after the last tap
    feedback?.let { shown ->
        LaunchedEffect(shown.id) {
            delay(700)
            feedback = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    this.player = player
                    keepScreenOn = true
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility -> controlsVisible = visibility == View.VISIBLE },
                    )
                    setFullscreenButtonClickListener { wanted -> fullscreenChange(wanted) }
                    // single tap shows or hides the controls, double tap on a side jumps back or forward;
                    // the buttons of the controls are children and still get their own touches first
                    val view = this
                    val gestures = GestureDetector(
                        viewContext,
                        object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent) = true

                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                if (view.isControllerFullyVisible) view.hideController() else view.showController()
                                return true
                            }

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                val third = view.width / 3f
                                val forward = when {
                                    e.x < third -> false
                                    e.x > view.width - third -> true
                                    else -> {
                                        // the middle pauses or plays again
                                        if (player.isPlaying) player.pause() else player.play()
                                        return true
                                    }
                                }
                                if (forward) player.seekForward() else player.seekBack()
                                val previous = feedback
                                val seconds = (SEEK_STEP_MS / 1000).toInt()
                                feedback = SeekFeedback(
                                    forward = forward,
                                    seconds = if (previous != null && previous.forward == forward) previous.seconds + seconds else seconds,
                                    id = System.nanoTime(),
                                )
                                return true
                            }
                        },
                    )
                    setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
                }
            },
            update = { it.setFullscreenButtonState(fullscreen) },
            modifier = Modifier.fillMaxSize(),
        )

        feedback?.let { shown ->
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.4f)
                    .align(if (shown.forward) Alignment.CenterEnd else Alignment.CenterStart),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (shown.forward) "+${shown.seconds} s" else "−${shown.seconds} s",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // in full screen the top bar is hidden: the X leaves full screen, shown together with the controls
        AnimatedVisibility(
            visible = fullscreen && controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f))
                    .clickable { fullscreenChange(false) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(AppIcons.Close, contentDescription = "Esci dallo schermo intero", tint = Color.White)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
