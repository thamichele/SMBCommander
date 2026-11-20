// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * ViewerScreen.kt - photos and videos of a folder, one per page
 *
 * Holds the pages, the top bar with the actions on the file, and the rules
 * for what happens to the video when the page turns. A photo is drawn here;
 * a video page is drawn by VideoPage.kt with the player of VideoPlayerState.kt.
 * It reads the NAS only through the image loader and the player.
 *
 * Version: 1.21.0
 */
package io.github.thamichele.smbcommander.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.ImageSize
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.data.isVideo
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioType
import kotlinx.coroutines.delay

/*
 * NOTE: file-private constants on purpose. Each belongs to this screen only,
 * and a shared "config" file would be the first thing every screen imports.
 */

/** How long the bar of a video stays in full screen after the last touch, while the video runs. */
private const val CHROME_TIMEOUT_MS = 3_500L

/** Black veil behind the top bar, so it reads on any photo. */
private const val TOP_BAR_SCRIM_ALPHA = 0.55f
private const val TOP_BAR_DETAIL_ALPHA = 0.7f

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/**
 * ViewerScreen() - photos and videos of a folder, one per page
 * @param vm the app's view model: actions on the file and the settings
 * @param screen the list of files and the one to open first
 *
 * A photo shows the full-size copy cached on the phone (same pixels as the
 * original, JPEG compressed), with the thumbnail as placeholder while it is
 * made; a video streams from the NAS when played. The original is opened only
 * on request.
 *
 * The pages turn while a video plays too. What the next video does is decided
 * in VideoAutoStart().
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun ViewerScreen(vm: AppViewModel, screen: Screen.Viewer) {
    if (screen.images.isEmpty()) return
    val context = LocalContext.current
    val extras by vm.extras.collectAsStateWithLifecycle()
    val pager = rememberPagerState(initialPage = screen.index) { screen.images.size }
    val video = rememberVideoPlayerState()
    val current = screen.images[pager.currentPage.coerceIn(0, screen.images.lastIndex)]
    // the page the swipe came to rest on: a page only passed through never loads its video
    val settled = screen.images[pager.settledPage.coerceIn(0, screen.images.lastIndex)]
    // the bars of the viewer: the top bar and, in full screen, the bar of the video
    var isChromeVisible by remember { mutableStateOf(true) }
    // system bars and top bar hidden, wide videos turn the phone; only while a video is loaded
    var isFullscreen by remember { mutableStateOf(false) }
    // raised by every touch on the bar of the video: restarts the countdown that hides it
    var interactions by remember { mutableIntStateOf(0) }
    var fit by remember { mutableStateOf(VideoFit.load(context)) }

    val leaveFullscreen = {
        isFullscreen = false
        // the bar of the video may have hidden itself: outside full screen the top bar is back
        isChromeVisible = true
    }
    VideoAutoStart(settled, video, extras.autoplayVideo, onOpenedPlaying = { isFullscreen = true })
    LaunchedEffect(video.entry == null) {
        if (video.entry == null && isFullscreen) leaveFullscreen()
    }
    if (isFullscreen) {
        FullscreenEffect(video.isLandscape)
        ChromeTimeout(isChromeVisible && video.isPlaying, interactions) { isChromeVisible = false }
    }
    BackHandler(enabled = video.entry != null) { video.stop() }
    // declared after the one above, so it wins: back first leaves full screen, then stops the video
    BackHandler(enabled = isFullscreen) { leaveFullscreen() }

    val pageState = VideoPageState(isFullscreen, isChromeVisible, fit)
    val pageActions = remember(video) {
        VideoPageActions(
            onStart = { entry ->
                video.start(entry)
                isFullscreen = true
                isChromeVisible = true
            },
            onToggleChrome = { isChromeVisible = !isChromeVisible },
            onFullscreenChange = { wanted -> if (wanted) isFullscreen = true else leaveFullscreen() },
            onNextFit = {
                fit = fit.next()
                VideoFit.save(context, fit)
            },
            onInteraction = { interactions++ },
        )
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, beyondViewportPageCount = 1, modifier = Modifier.fillMaxSize()) { page ->
            val entry = screen.images[page]
            if (!entry.isVideo) {
                ZoomableImage(entry, onTap = { isChromeVisible = !isChromeVisible })
            } else {
                VideoPage(entry, video, pageState, pageActions)
            }
        }
        AnimatedVisibility(visible = isChromeVisible && !isFullscreen, enter = fadeIn(), exit = fadeOut()) {
            ViewerTopBar(
                vm = vm,
                entry = current,
                position = "${pager.currentPage + 1} di ${screen.images.size}",
                onBack = { if (video.entry != null) video.stop() else vm.back() },
            )
        }
    }
}

/*
 * Private: decides what the player does each time the pages come to rest on
 * another file (settled). A photo stops it. A video starts by itself when the setting says so
 * or when the page was turned away from a video that was running; else it
 * waits for a tap. onOpenedPlaying is called when the very first page of the
 * viewer starts by itself: that video opens in full screen, like a tap on Play.
 */
@Composable
private fun VideoAutoStart(
    settled: RemoteEntry,
    video: VideoPlayerState,
    isAutoplayOn: Boolean,
    onOpenedPlaying: () -> Unit,
) {
    var isFirstPage by remember { mutableStateOf(true) }
    LaunchedEffect(settled) {
        val wasFirstPage = isFirstPage
        isFirstPage = false
        // the page just left was running: keep watching on the next video
        val isWatching = video.isPlaying
        if (!settled.isVideo || !(isAutoplayOn || isWatching)) {
            video.stop()
            return@LaunchedEffect
        }
        if (video.entry != settled) video.start(settled)
        if (wasFirstPage) onOpenedPlaying()
    }
}

/* Private: hides the bars a while after the last touch; isCounting is false while they must stay. */
@Composable
private fun ChromeTimeout(isCounting: Boolean, interactions: Int, onTimeout: () -> Unit) {
    LaunchedEffect(isCounting, interactions) {
        if (!isCounting) return@LaunchedEffect
        delay(CHROME_TIMEOUT_MS)
        onTimeout()
    }
}

/* Private: name of the file, its place in the list and the actions on it. entry is the page on screen. */
@Composable
private fun ViewerTopBar(vm: AppViewModel, entry: RemoteEntry, position: String, onBack: () -> Unit) {
    var pendingDownload by remember { mutableStateOf<RemoteEntry?>(null) }
    var confirmDelete by remember { mutableStateOf<RemoteEntry?>(null) }
    var info by remember { mutableStateOf<RemoteEntry?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val wanted = pendingDownload
        pendingDownload = null
        if (uri != null && wanted != null) vm.downloadTo(wanted, uri)
    }
    val white = Color.White
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = TOP_BAR_SCRIM_ALPHA))
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolButton(AppIcons.Back, "Indietro", onBack, tint = white)
        ViewerTitle(entry, position, Modifier.weight(1f).padding(start = 4.dp))
        ToolButton(AppIcons.Info, "Informazioni", { info = entry }, tint = white)
        ToolButton(AppIcons.Share, "Condividi", { vm.share(listOf(entry)) }, tint = white)
        ToolButton(AppIcons.OpenWith, "Apri l'originale", { vm.open(entry, edit = false) }, tint = white)
        ToolButton(
            AppIcons.Download,
            "Salva l'originale sul telefono",
            {
                pendingDownload = entry
                saveFile.launch(entry.name)
            },
            tint = white,
        )
        ToolButton(AppIcons.Delete, "Elimina dal NAS", { confirmDelete = entry }, tint = white)
    }
    info?.let { InfoDialog(it, onDismiss = { info = null }) }
    confirmDelete?.let { doomed ->
        DeleteFromNasDialog(doomed, onDismiss = { confirmDelete = null }) {
            confirmDelete = null
            vm.deletePhotos(listOf(doomed))
        }
    }
}

/* Private: the two lines of text of the top bar. */
@Composable
private fun ViewerTitle(entry: RemoteEntry, position: String, modifier: Modifier) {
    Column(modifier) {
        Text(
            entry.name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "$position · ${entryDetails(entry)}",
            style = ArancioType.data,
            color = Color.White.copy(alpha = TOP_BAR_DETAIL_ALPHA),
            maxLines = 1,
        )
    }
}

/* Private: asks before a photo or a video is deleted from the NAS. */
@Composable
private fun DeleteFromNasDialog(entry: RemoteEntry, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val what = if (entry.isVideo) "Il video" else "La foto"
    ConfirmDialog(
        title = "Eliminare «${entry.name}»?",
        text = "$what verrà eliminato dal NAS. Se era sul telefono, lì resta e il backup non lo " +
            "caricherà di nuovo.",
        confirm = "Elimina",
        danger = true,
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

/* Private: a photo that can be pinched and double tapped to zoom; a single tap is passed to onTap. */
@Composable
private fun ZoomableImage(entry: RemoteEntry, onTap: () -> Unit) {
    var scale by remember { mutableFloatStateOf(MIN_ZOOM) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
        offset = if (scale == MIN_ZOOM) Offset.Zero else offset + pan
    }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(entry) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        if (scale > MIN_ZOOM) {
                            scale = MIN_ZOOM
                            offset = Offset.Zero
                        } else {
                            scale = DOUBLE_TAP_ZOOM
                        }
                    },
                )
            }
            // panning only while zoomed in: at normal size horizontal drags turn the page
            .transformable(state, canPan = { scale > MIN_ZOOM }),
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
