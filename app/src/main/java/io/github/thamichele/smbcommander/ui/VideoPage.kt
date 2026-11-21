// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * VideoPage.kt - one video of the viewer: picture, touches and control bar
 *
 * Draws a page of the viewer that holds a video: the still frame or the
 * running picture, the play button in the middle while it is not running,
 * and the bar of buttons under the picture. Nothing but that play button is
 * drawn over the video. The player itself lives in VideoPlayerState.kt; the
 * pages, the top bar and the rules for turning the page are in ViewerScreen.kt.
 *
 * Version: 1.22.0
 */
package io.github.thamichele.smbcommander.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import io.github.thamichele.smbcommander.core.PlaybackTime
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.ImageSize
import io.github.thamichele.smbcommander.data.Thumb
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioTheme
import io.github.thamichele.smbcommander.ui.theme.ArancioType
import kotlinx.coroutines.delay

/** How far a double tap or a jump button moves the video. */
private const val SEEK_STEP_MS = 10_000L
private const val SEEK_STEP_LABEL = "10 s"
private const val MS_PER_SECOND = 1_000L

/** How long the "+10 s" label stays after the last double tap. */
private const val SEEK_FEEDBACK_MS = 700L

/** How often the clock of the bar is read from the player while it is on screen. */
private const val CLOCK_TICK_MS = 250L

/** From this width on (a phone held sideways) the bar is one row instead of two. */
private val WIDE_BAR_MIN_WIDTH = 560.dp

private val CENTER_BUTTON_SIZE = 88.dp
private val CENTER_ICON_SIZE = 44.dp
private val SPINNER_SIZE = 48.dp
private val SPINNER_STROKE = 3.dp

/** Black veil behind the things drawn over the picture, so they read on any video. */
private const val SCRIM_ALPHA = 0.55f

/** Part of the width, on each side, where a double tap jumps instead of pausing. */
private const val SEEK_ZONE_FRACTION = 1f / 3f
private const val SEEK_LABEL_WIDTH_FRACTION = 0.4f

private const val VIEWER_PREFS = "viewer"
private const val KEY_VIDEO_FIT = "video_fit"

/** Ways a video can fill the screen in full screen, in the order the button goes through them. */
@OptIn(UnstableApi::class)
enum class VideoFit(val label: String, val resizeMode: Int, val ratio: Float? = null) {
    /** whole video visible, black bars where the shapes differ */
    Fit("Adatta", AspectRatioFrameLayout.RESIZE_MODE_FIT),

    /** whole screen covered, the edges of the video cut off */
    Crop("Ritaglia", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),

    /** whole screen covered, the video stretched out of shape */
    Stretch("Allunga", AspectRatioFrameLayout.RESIZE_MODE_FILL),

    Wide("16:9", AspectRatioFrameLayout.RESIZE_MODE_FILL, 16f / 9f),
    Classic("4:3", AspectRatioFrameLayout.RESIZE_MODE_FILL, 4f / 3f),
    ;

    /**
     * next() - the way of filling the screen that follows this one
     *
     * Return: the next entry, the first one after the last.
     */
    fun next(): VideoFit = entries[(ordinal + 1) % entries.size]

    companion object {
        /**
         * load() - the way of filling the screen chosen last time
         * @param context any context of the app
         *
         * Return: the saved choice, [Fit] when nothing valid is saved.
         */
        fun load(context: Context): VideoFit {
            val saved = prefs(context).getString(KEY_VIDEO_FIT, null)
            return entries.firstOrNull { it.name == saved } ?: Fit
        }

        /**
         * save() - keeps the choice for the next videos
         * @param context any context of the app
         * @param fit the choice to keep
         *
         * Return: nothing.
         */
        fun save(context: Context, fit: VideoFit) {
            prefs(context).edit().putString(KEY_VIDEO_FIT, fit.name).apply()
        }

        private fun prefs(context: Context) =
            context.getSharedPreferences(VIEWER_PREFS, Context.MODE_PRIVATE)
    }
}

/**
 * VideoPageState - what one video page needs to know about the viewer
 * @param isFullscreen system bars hidden; the bar comes and goes with [isChromeVisible]
 * @param isChromeVisible the viewer's bars are asked for (a tap on the video flips it)
 * @param fit how the picture fills the screen in full screen
 */
data class VideoPageState(val isFullscreen: Boolean, val isChromeVisible: Boolean, val fit: VideoFit)

/**
 * VideoPageActions - what a video page asks the viewer to do
 * @param onStart play the video of a page that is not the loaded one yet
 * @param onToggleChrome show or hide the viewer's bars
 * @param onFullscreenChange enter (true) or leave (false) full screen
 * @param onNextFit move to the next way of filling the screen
 * @param onInteraction a button of the bar was used: keep the bar on screen
 */
class VideoPageActions(
    val onStart: (RemoteEntry) -> Unit,
    val onToggleChrome: () -> Unit,
    val onFullscreenChange: (Boolean) -> Unit,
    val onNextFit: () -> Unit,
    val onInteraction: () -> Unit,
)

/** A double tap jumped: which way, how far in total (taps in a row add up), and a counter that restarts the label. */
private class SeekFeedback(val isForward: Boolean, val seconds: Int, val id: Long)

/**
 * VideoPage() - a page of the viewer that holds a video
 * @param entry the video of this page
 * @param player the viewer's player; this page is "active" when [entry] is loaded in it
 * @param state what the viewer shows now
 * @param actions what the page asks the viewer to do
 *
 * The picture takes all the room the bar leaves: the bar is under the video,
 * never over it. A page that is not active shows its still frame and the bar
 * with only Play alive, so the next video looks the same before it starts.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun VideoPage(entry: RemoteEntry, player: VideoPlayerState, state: VideoPageState, actions: VideoPageActions) {
    val isActive = player.entry == entry
    val isRunning = isActive && player.isPlaying && player.error == null
    // dark tokens whatever the theme: the viewer is always black
    ArancioTheme(dark = true) {
        Column(Modifier.fillMaxSize()) {
            VideoStage(entry, player, isActive, state, actions, Modifier.weight(1f).fillMaxWidth())
            AnimatedVisibility(
                visible = !state.isFullscreen || state.isChromeVisible || !isRunning,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                VideoControlBar(entry, player, state, actions)
            }
        }
    }
}

/**
 * FullscreenEffect() - hides the system bars and turns the screen for wide videos
 * @param isLandscape the video is wider than tall
 *
 * Everything is put back as it was when the effect leaves the composition.
 *
 * Context: composable; changes the window of the activity.
 * Return: nothing.
 */
@Composable
fun FullscreenEffect(isLandscape: Boolean) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() } ?: return
    DisposableEffect(activity) {
        val window = activity.window
        val bars = WindowCompat.getInsetsController(window, window.decorView)
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars.show(WindowInsetsCompat.Type.systemBars()) }
    }
    // a tall video leaves the orientation alone: only a wide one turns the screen
    DisposableEffect(activity, isLandscape) {
        val orientationBefore = activity.requestedOrientation
        if (isLandscape) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { activity.requestedOrientation = orientationBefore }
    }
}

/* Private: the picture and what is drawn over it. isActive means entry is the video loaded in player. */
@Composable
private fun VideoStage(
    entry: RemoteEntry,
    player: VideoPlayerState,
    isActive: Boolean,
    state: VideoPageState,
    actions: VideoPageActions,
    modifier: Modifier,
) {
    val isRunning = isActive && player.isPlaying && player.error == null
    var feedback by remember { mutableStateOf<SeekFeedback?>(null) }
    SeekFeedbackTimeout(feedback) { feedback = null }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (isActive) VideoSurface(player, if (state.isFullscreen) state.fit else VideoFit.Fit)
        // while any video is loaded the NAS serves that one: no sharp frames for the pages around it
        if (!isActive || !player.hasFrame) StillFrame(entry, isSharp = player.entry == null)
        StageTouches(
            isRunning = isRunning,
            onTap = {
                when {
                    !isActive -> actions.onStart(entry)
                    isRunning -> actions.onToggleChrome()
                    else -> player.togglePlay()
                }
            },
            onSeek = { isForward ->
                player.seekBy(if (isForward) SEEK_STEP_MS else -SEEK_STEP_MS)
                feedback = nextFeedback(feedback, isForward)
            },
            onPause = player::togglePlay,
        )
        StageStatus(player, isActive, isRunning)
        feedback?.let { SeekFeedbackLabel(it) }
    }
}

/* Private: clears the label a moment after the last double tap; shown may be null. */
@Composable
private fun SeekFeedbackTimeout(shown: SeekFeedback?, onTimeout: () -> Unit) {
    val timeout by rememberUpdatedState(onTimeout)
    if (shown == null) return
    LaunchedEffect(shown.id) {
        delay(SEEK_FEEDBACK_MS)
        timeout()
    }
}

/* Private: taps in a row on the same side add up ("+20 s"); previous may be null. */
private fun nextFeedback(previous: SeekFeedback?, isForward: Boolean): SeekFeedback {
    val step = (SEEK_STEP_MS / MS_PER_SECOND).toInt()
    val soFar = previous?.takeIf { it.isForward == isForward }?.seconds ?: 0
    return SeekFeedback(isForward, soFar + step, System.nanoTime())
}

/*
 * Private: the running picture. The PlayerView only draws: its own buttons
 * are off and it takes no touches, so the pages still swipe under the finger.
 */
@OptIn(UnstableApi::class)
@Composable
private fun VideoSurface(player: VideoPlayerState, fit: VideoFit) {
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply { useController = false }
        },
        update = { view ->
            view.player = player.player
            view.resizeMode = fit.resizeMode
            // the screen may sleep again as soon as the video is paused
            view.keepScreenOn = player.isPlaying
        },
        onRelease = { view -> view.player = null },
        // a fixed shape (16:9, 4:3) is the player's own size, with the video stretched into it
        modifier = fit.ratio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
    )
}

/*
 * Private: the picture of a video that is not running yet. The small
 * thumbnail is already in the cache from the list and shows at once; the
 * sharp frame is read from the NAS only when asked (isSharp), because that
 * read would compete with a video that is playing or about to.
 */
@Composable
private fun StillFrame(entry: RemoteEntry, isSharp: Boolean) {
    val small = remember(entry) { Thumb(entry, ImageSize.Thumb) }
    if (!isSharp) {
        AsyncImage(
            model = small,
            contentDescription = entry.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }
    SubcomposeAsyncImage(
        model = remember(entry) { Thumb(entry, ImageSize.Preview) },
        contentDescription = entry.name,
        contentScale = ContentScale.Fit,
        loading = {
            AsyncImage(
                model = small,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/*
 * Private: the touches on the picture. A tap anywhere does the obvious thing
 * (start, resume, or show the bars while running). Double taps exist only
 * while the video runs, so every other tap acts at once instead of waiting
 * to see whether a second one follows.
 */
@Composable
private fun StageTouches(
    isRunning: Boolean,
    onTap: () -> Unit,
    onSeek: (isForward: Boolean) -> Unit,
    onPause: () -> Unit,
) {
    val tap by rememberUpdatedState(onTap)
    val seek by rememberUpdatedState(onSeek)
    val pause by rememberUpdatedState(onPause)
    val label = if (isRunning) "Video in riproduzione" else "Riproduci il video"
    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = label }
            .pointerInput(isRunning) {
                val onDoubleTap: (Offset) -> Unit = { offset ->
                    val zone = size.width * SEEK_ZONE_FRACTION
                    when {
                        offset.x < zone -> seek(false)
                        offset.x > size.width - zone -> seek(true)
                        else -> pause()
                    }
                }
                detectTapGestures(onTap = { tap() }, onDoubleTap = onDoubleTap.takeIf { isRunning })
            },
    )
}

/* Private: what the picture says about the player: an error, waiting for data, or "paused" (the play button). */
@Composable
private fun StageStatus(player: VideoPlayerState, isActive: Boolean, isRunning: Boolean) {
    val error = if (isActive) player.error else null
    when {
        error != null -> Text(
            "$error\nTocca per riprovare.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(24.dp)
                .clip(ArancioShape.card)
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .padding(16.dp),
        )
        isRunning && (player.isBuffering || !player.hasFrame) -> CircularProgressIndicator(
            color = Arancio.colors.accent,
            strokeWidth = SPINNER_STROKE,
            modifier = Modifier.size(SPINNER_SIZE),
        )
        !isRunning -> CenterPlayMark()
    }
}

/* Private: the only thing drawn over a video, and only while it does not run. The tap is taken by StageTouches. */
@Composable
private fun CenterPlayMark() {
    Box(
        Modifier
            .size(CENTER_BUTTON_SIZE)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = SCRIM_ALPHA)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            AppIcons.Play,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(CENTER_ICON_SIZE),
        )
    }
}

/* Private: "+10 s" in the middle of the side that was tapped. */
@Composable
private fun SeekFeedbackLabel(shown: SeekFeedback) {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = if (shown.isForward) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(SEEK_LABEL_WIDTH_FRACTION),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (shown.isForward) "+${shown.seconds} s" else "−${shown.seconds} s",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier
                    .clip(ArancioShape.pill)
                    .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/*
 * Private: the bar under the video. From the left: play or pause, back,
 * forward; on the right the way of filling the screen (full screen only) and
 * full screen. The seek line is a row of its own on a narrow screen and sits
 * between the two groups on a wide one.
 */
@Composable
private fun VideoControlBar(
    entry: RemoteEntry,
    player: VideoPlayerState,
    state: VideoPageState,
    actions: VideoPageActions,
) {
    val isActive = player.entry == entry
    if (isActive) ClockTicker(player)
    BoxWithConstraints(Modifier.fillMaxWidth().background(Arancio.colors.nav)) {
        val isWide = maxWidth >= WIDE_BAR_MIN_WIDTH
        val padding = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        if (isWide) {
            Row(padding, verticalAlignment = Alignment.CenterVertically) {
                TransportButtons(entry, player, actions)
                SeekLine(player, isActive, actions.onInteraction, Modifier.weight(1f))
                ViewButtons(state, actions)
            }
        } else {
            Column(padding) {
                SeekLine(player, isActive, actions.onInteraction, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TransportButtons(entry, player, actions)
                    Spacer(Modifier.weight(1f))
                    ViewButtons(state, actions)
                }
            }
        }
    }
}

/* Private: keeps the clock of the bar moving, only while the bar is on screen. */
@Composable
private fun ClockTicker(player: VideoPlayerState) {
    LaunchedEffect(player, player.isPlaying) {
        player.refreshPosition()
        while (player.isPlaying) {
            delay(CLOCK_TICK_MS)
            player.refreshPosition()
        }
    }
}

/* Private: play or pause first, then back and forward. On a page that is not active only Play is alive. */
@Composable
private fun TransportButtons(entry: RemoteEntry, player: VideoPlayerState, actions: VideoPageActions) {
    val isActive = player.entry == entry
    val isRunning = isActive && player.isPlaying && player.error == null
    val used = { action: () -> Unit ->
        actions.onInteraction()
        action()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        ToolButton(
            icon = if (isRunning) AppIcons.Pause else AppIcons.Play,
            label = if (isRunning) "Pausa" else "Riproduci",
            onClick = { used { if (isActive) player.togglePlay() else actions.onStart(entry) } },
            isPrimary = true,
        )
        ToolButton(
            icon = AppIcons.Rewind,
            label = "Indietro di 10 secondi",
            onClick = { used { player.seekBy(-SEEK_STEP_MS) } },
            enabled = isActive,
            caption = SEEK_STEP_LABEL,
        )
        ToolButton(
            icon = AppIcons.FastForward,
            label = "Avanti di 10 secondi",
            onClick = { used { player.seekBy(SEEK_STEP_MS) } },
            enabled = isActive,
            caption = SEEK_STEP_LABEL,
        )
    }
}

/* Private: the way of filling the screen (it only matters in full screen) and the full screen button. */
@Composable
private fun ViewButtons(state: VideoPageState, actions: VideoPageActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.isFullscreen) {
            ToolButton(
                icon = AppIcons.AspectRatio,
                label = "Adattamento allo schermo: ${state.fit.label}",
                onClick = {
                    actions.onInteraction()
                    actions.onNextFit()
                },
                caption = state.fit.label,
            )
        }
        ToolButton(
            icon = if (state.isFullscreen) AppIcons.FullscreenExit else AppIcons.Fullscreen,
            label = if (state.isFullscreen) "Esci dallo schermo intero" else "Schermo intero",
            onClick = {
                actions.onInteraction()
                actions.onFullscreenChange(!state.isFullscreen)
            },
        )
    }
}

/*
 * Private: position, seek line, length. While the finger drags, the line
 * follows the finger and the video jumps once, when the finger lifts: one
 * request to the NAS instead of one per pixel.
 */
@Composable
private fun SeekLine(player: VideoPlayerState, isActive: Boolean, onInteraction: () -> Unit, modifier: Modifier) {
    var dragged by remember { mutableStateOf<Float?>(null) }
    val durationMs = if (isActive) player.durationMs else 0L
    val positionMs = if (isActive) player.positionMs else 0L
    val fraction = dragged ?: PlaybackTime.fraction(positionMs, durationMs)
    val shownMs = if (dragged != null) PlaybackTime.positionAt(fraction, durationMs) else positionMs
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        ClockLabel(PlaybackTime.format(shownMs))
        Slider(
            value = fraction,
            onValueChange = {
                dragged = it
                onInteraction()
            },
            onValueChangeFinished = {
                dragged?.let { player.seekTo(PlaybackTime.positionAt(it, durationMs)) }
                dragged = null
            },
            enabled = durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = Arancio.colors.accent,
                activeTrackColor = Arancio.colors.accent,
                inactiveTrackColor = Arancio.colors.borderStrong,
                disabledThumbColor = Arancio.colors.textDisabled,
                disabledActiveTrackColor = Arancio.colors.border,
                disabledInactiveTrackColor = Arancio.colors.border,
            ),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        ClockLabel(if (durationMs > 0) PlaybackTime.format(durationMs) else "–:––")
    }
}

/* Private: a time of the seek line, in the kit's data type so the digits do not dance. */
@Composable
private fun ClockLabel(text: String) {
    Text(text, style = ArancioType.data, color = Arancio.colors.textSecondary, maxLines = 1)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
