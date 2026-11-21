// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * VideoFrameCycle.kt - the animated preview of a video in the lists, and its switch in the settings
 *
 * Drawn over the still thumbnail of a video: once the preview has stayed on
 * screen for a moment, it asks VideoFrames for two or three frames and shows
 * them in turn with a short fade. Only previews on screen exist in the lazy
 * lists, so only those animate; the cycle stops when the app is not in front.
 * It reads nothing itself and draws nothing until the frames are ready, so
 * the thumbnail under it shows as before.
 *
 * Version: 1.27.0
 */
package io.github.thamichele.smbcommander.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import coil3.compose.AsyncImage
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.ui.theme.Arancio
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.abs

/* A preview flown past while scrolling never reads the NAS: it must stay this long first. */
private const val SETTLE_MS = 600L

/* How long each frame stays, and how long the fade between two frames lasts. */
private const val FRAME_MS = 1_400L
private const val FADE_MS = 350

/**
 * VideoFrameCycle() - shows the frames of a video in turn, over its still thumbnail
 * @param video a video on the NAS
 * @param modifier size and shape: the same as the thumbnail under it
 * @param contentScale how the frames fill the space, as the thumbnail does
 *
 * Nothing is drawn while the setting is off, while the frames are not ready,
 * or when the video has fewer than two: the thumbnail shows instead.
 *
 * Context: composable; reads the NAS through VideoFrames, at most once per video.
 * Return: nothing.
 */
@Composable
fun VideoFrameCycle(video: RemoteEntry, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val app = LocalContext.current.applicationContext as SmbCommanderApp
    val isEnabled by app.videoFramePrefs.enabled.collectAsStateWithLifecycle()
    if (!isEnabled) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var frames by remember(video) { mutableStateOf<List<File>>(emptyList()) }
    var shown by remember(video) { mutableIntStateOf(-1) }
    LaunchedEffect(video) {
        delay(SETTLE_MS)
        frames = app.videoFrames.get(video)
        if (frames.size < 2) return@LaunchedEffect
        // previews side by side do not change all at the same instant
        delay(abs(video.path.hashCode().toLong()) % FRAME_MS)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                shown = (shown + 1) % frames.size
                delay(FRAME_MS)
            }
        }
    }
    if (shown < 0) return
    Crossfade(targetState = shown, animationSpec = tween(FADE_MS), modifier = modifier, label = "frames") { i ->
        AsyncImage(
            model = frames.getOrNull(i),
            contentDescription = null,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * VideoFrameOptions() - the switch of the animated previews, for the settings page "Opzioni"
 *
 * Context: composable; the change is saved at once and every preview follows it.
 * Return: nothing.
 */
@Composable
fun VideoFrameOptions() {
    val app = LocalContext.current.applicationContext as SmbCommanderApp
    val prefs = app.videoFramePrefs
    val isEnabled by prefs.enabled.collectAsStateWithLifecycle()
    SectionLabel("Video", Modifier.padding(top = 20.dp))
    Panel(padding = PaddingValues(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { prefs.setEnabled(!isEnabled) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Anteprime animate dei video", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Nella Galleria e in File le anteprime dei video mostrano a turno due o tre momenti del video " +
                        "(dopo 2 secondi, dopo 10, a metà). I fotogrammi si leggono dal NAS una volta sola e restano " +
                        "nella cache delle miniature; mentre guardi un video aspettano.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Arancio.colors.textSecondary,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = isEnabled, onCheckedChange = prefs::setEnabled, colors = arancioSwitchColors())
        }
    }
}
