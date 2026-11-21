package io.github.thamichele.smbcommander.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.thamichele.smbcommander.data.MusicState
import io.github.thamichele.smbcommander.ui.theme.Arancio


/** The song playing, over every screen: tap to show the seek bar; play/pause, next, previous and close. */
@Composable
fun MiniPlayer(
    music: MusicState,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Arancio.colors
    var open by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    Column(
        modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .floating(c)
            .clip(RoundedCornerShape(12.dp))
            .clickable { open = !open },
    ) {
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.media.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                val cover = music.cover
                if (cover != null) {
                    Image(cover.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(44.dp))
                } else {
                    Icon(AppIcons.Music, contentDescription = null, tint = c.media, modifier = Modifier.size(22.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(music.title.ifEmpty { "Musica" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    music.error ?: listOf(music.subtitle, "${music.index + 1} di ${music.count}").filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (music.error != null) c.danger else c.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!open) {
                PlayButton(music.playing, onToggle)
                ToolButton(AppIcons.Next, "Brano successivo", onNext)
            }
        }
        // opened: the four buttons get a row of their own, so the title keeps its room
        if (open) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(AppIcons.Previous, "Brano precedente", onPrevious)
                PlayButton(music.playing, onToggle)
                ToolButton(AppIcons.Next, "Brano successivo", onNext)
                ToolButton(AppIcons.Close, "Chiudi il lettore", onClose)
            }
        }
        val fraction = if (music.duration > 0) (music.position.toFloat() / music.duration).coerceIn(0f, 1f) else 0f
        if (open && music.duration > 0) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                Slider(
                    value = dragging ?: fraction,
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        dragging?.let { onSeek((it * music.duration).toLong()) }
                        dragging = null
                    },
                    colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.border),
                )
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(time(((dragging ?: fraction) * music.duration).toLong()), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                    Text(time(music.duration), style = MaterialTheme.typography.labelSmall, color = c.textSecondary)
                }
            }
        } else if (music.buffering) {
            ArancioProgress(null)
        } else {
            ArancioProgress(fraction)
        }
    }
}

/* Private: play or pause, filled with the main colour like the Play of the video player. */
@Composable
private fun PlayButton(isPlaying: Boolean, onToggle: () -> Unit) = ToolButton(
    if (isPlaying) AppIcons.Pause else AppIcons.Play,
    if (isPlaying) "Pausa" else "Riproduci",
    onToggle,
    isPrimary = true,
)

private fun time(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
