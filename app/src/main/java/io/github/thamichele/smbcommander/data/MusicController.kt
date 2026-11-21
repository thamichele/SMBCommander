/*
 * MusicController.kt - the link between the screens and the music player service
 *
 * Starts and controls MusicService and turns its state into what the mini player shows.
 *
 * Version: 1.28.6
 */
package io.github.thamichele.smbcommander.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.core.RemotePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the mini player shows. */
data class MusicState(
    val title: String = "",
    val subtitle: String = "",
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val index: Int = 0,
    val count: Int = 0,
    val cover: Bitmap? = null,
    val error: String? = null,
) {
    val active: Boolean get() = count > 0
}

/** The app's side of [MusicService]: starts the songs of a folder and follows what plays. */
class MusicController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    private var connecting = false
    private val waiting = ArrayList<(MediaController) -> Unit>()
    private var ticker: Job? = null

    private val _state = MutableStateFlow(MusicState())
    val state: StateFlow<MusicState> = _state.asStateFlow()

    /**
     * play() - plays songs of a folder from one of them on
     * @param songs the songs, in order
     * @param start the index of the first one to play
     *
     * Return: nothing; the other songs follow.
     */
    fun play(songs: List<RemoteEntry>, start: Int) = withController { c ->
        c.setMediaItems(songs.map(::itemOf), start.coerceIn(0, songs.lastIndex), 0)
        c.prepare()
        c.play()
    }

    /**
     * toggle() - pauses or plays again
     *
     * Return: nothing.
     */
    fun toggle() = withController { if (it.isPlaying) it.pause() else it.play() }
    /**
     * next() - skips to the next song
     *
     * Return: nothing.
     */
    fun next() = withController { it.seekToNextMediaItem() }
    /**
     * previous() - goes back to the previous song
     *
     * Return: nothing.
     */
    fun previous() = withController { it.seekToPreviousMediaItem() }
    /**
     * seek() - jumps inside the song
     * @param position where, in milliseconds
     *
     * Return: nothing.
     */
    fun seek(position: Long) = withController { it.seekTo(position) }

    /**
     * stop() - stops and empties the queue: the mini player closes and the service goes
     *
     * Return: nothing.
     */
    fun stop() = withController {
        it.stop()
        it.clearMediaItems()
    }

    private fun itemOf(song: RemoteEntry): MediaItem {
        val folder = RemotePath.name(RemotePath.parent(song.path)).ifEmpty { song.share }
        return MediaItem.Builder()
            .setUri(SmbDataSource.uri(song.share, song.path))
            .setMediaId(song.share + "/" + song.path)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.name.substringBeforeLast('.'))
                    .setArtist(folder)
                    .build()
            )
            .build()
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { return block(it) }
        waiting += block
        if (connecting) return
        connecting = true
        val token = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                waiting.clear()
                _state.value = MusicState(error = "Il lettore musicale non si è avviato")
                return@addListener
            }
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = publish()
            })
            waiting.toList().also { waiting.clear() }.forEach { it(c) }
            publish()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun publish() {
        val c = controller ?: return
        val meta = c.mediaMetadata
        val cover = meta.artworkData?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 2 })
            }.getOrNull()
        }
        val previous = _state.value
        _state.value = MusicState(
            title = meta.title?.toString().orEmpty(),
            subtitle = listOfNotNull(meta.artist?.toString(), meta.albumTitle?.toString()).distinct().joinToString(" · "),
            playing = c.isPlaying,
            buffering = c.playbackState == Player.STATE_BUFFERING,
            position = c.currentPosition.coerceAtLeast(0),
            duration = c.duration.takeIf { it > 0 } ?: 0,
            index = c.currentMediaItemIndex,
            count = c.mediaItemCount,
            // decoded once per song, not at every tick
            cover = if (previous.title == meta.title?.toString() && previous.cover != null) previous.cover else cover,
            error = c.playerError?.let { "Non riesco a riprodurre il brano: ${it.errorCodeName}" },
        )
        if (c.isPlaying && ticker?.isActive != true) {
            ticker = scope.launch {
                while (isActive && controller?.isPlaying == true) {
                    delay(500)
                    _state.value = _state.value.copy(position = controller?.currentPosition?.coerceAtLeast(0) ?: 0)
                }
            }
        }
    }
}
