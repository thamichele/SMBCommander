// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * VideoPlayerState.kt - the one video player of the viewer, as Compose state
 *
 * Owns a single ExoPlayer for the whole viewer and mirrors what the screens
 * need (which video is loaded, playing or paused, position, errors) into
 * Compose state. It draws nothing: VideoPage.kt shows the picture and the
 * buttons. The video streams from the NAS through SmbDataSource.
 *
 * Version: 1.20.0
 */
package io.github.thamichele.smbcommander.ui

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.thamichele.smbcommander.SmbCommanderApp
import io.github.thamichele.smbcommander.core.PlaybackTime
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.NasRepository
import io.github.thamichele.smbcommander.data.SmbDataSource

private const val LOG_TAG = "VideoPlayer"

/*
 * How much video is read ahead. The NAS is on the local network, so half a
 * second is enough to start and the picture appears almost at once; the
 * player's own defaults are meant for the Internet and wait longer.
 */
private const val MIN_BUFFER_MS = 15_000
private const val MAX_BUFFER_MS = 50_000
private const val START_BUFFER_MS = 500
private const val REBUFFER_MS = 1_500

/**
 * VideoPlayerState - the viewer's video player and what the screen shows of it
 *
 * One instance serves every video of the viewer: turning the page loads the
 * next video into the same player instead of building a new one each time.
 * The player itself is built at the first video, so a viewer that only shows
 * photos never pays for it.
 *
 * Context: main thread only, like the player it wraps.
 */
@OptIn(UnstableApi::class)
@Stable
class VideoPlayerState(private val context: Context, private val repository: NasRepository) {

    /** The video loaded in the player, null while there is none. */
    var entry by mutableStateOf<RemoteEntry?>(null)
        private set

    /** True while the video should run: stays true while it waits for data. */
    var isPlaying by mutableStateOf(false)
        private set

    /** True while the player waits for data from the NAS. */
    var isBuffering by mutableStateOf(false)
        private set

    /** True from the first picture of [entry] on: until then the still frame covers the player. */
    var hasFrame by mutableStateOf(false)
        private set

    /** True when the video is wider than tall. Keeps its value between videos so the screen does not turn twice. */
    var isLandscape by mutableStateOf(false)
        private set

    /** Message for the user when [entry] cannot be played, else null. */
    var error by mutableStateOf<String?>(null)
        private set

    var positionMs by mutableLongStateOf(0L)
        private set

    /** Length of [entry], 0 until the player knows it. */
    var durationMs by mutableLongStateOf(0L)
        private set

    /** The player for the picture; null until the first video starts. */
    var player: ExoPlayer? = null
        private set

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            isPlaying = playWhenReady
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            isBuffering = playbackState == Player.STATE_BUFFERING
            if (playbackState == Player.STATE_READY) refreshPosition()
            if (playbackState == Player.STATE_ENDED) stop()
        }

        override fun onRenderedFirstFrame() {
            hasFrame = true
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            // the player reports 0x0 between videos: not a shape
            if (videoSize.width <= 0 || videoSize.height <= 0) return
            isLandscape = videoSize.width * videoSize.pixelWidthHeightRatio > videoSize.height
        }

        override fun onPlayerError(playbackError: PlaybackException) {
            // the code only: the cause may carry the path of the file
            Log.w(LOG_TAG, "playback failed: ${playbackError.errorCodeName}")
            error = messageFor(playbackError)
            isBuffering = false
        }
    }

    /**
     * start() - loads a video and plays it from the beginning
     * @param video a video on the NAS; the caller has checked it is one
     *
     * Replaces the video loaded before, if any.
     *
     * Context: main thread.
     * Return: nothing.
     */
    fun start(video: RemoteEntry) {
        val exo = player ?: buildPlayer().also { player = it }
        entry = video
        resetProgress()
        exo.setMediaItem(MediaItem.fromUri(SmbDataSource.uri(video.share, video.path)))
        exo.prepare()
        exo.playWhenReady = true
    }

    /**
     * stop() - unloads the video and closes its file on the NAS
     *
     * The player stays ready for the next video. Does nothing without a video.
     *
     * Context: main thread.
     * Return: nothing.
     */
    fun stop() {
        if (entry == null) return
        entry = null
        resetProgress()
        player?.run {
            playWhenReady = false
            stop()
            clearMediaItems()
        }
    }

    /**
     * togglePlay() - pauses a running video, resumes a paused one
     *
     * After an error it loads the video again: the tap is the retry.
     *
     * Context: main thread.
     * Return: nothing.
     */
    fun togglePlay() {
        val video = entry ?: return
        if (error != null) return start(video)
        player?.let { it.playWhenReady = !it.playWhenReady }
    }

    /**
     * pause() - pauses the video, if one is running
     *
     * Return: nothing.
     */
    fun pause() {
        player?.playWhenReady = false
    }

    /**
     * seekBy() - jumps back or forward from the current position
     * @param deltaMs how far, negative to go back
     *
     * Return: nothing.
     */
    fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        seekTo(PlaybackTime.seekTarget(exo.currentPosition, deltaMs, durationMs))
    }

    /**
     * seekTo() - jumps to a position
     * @param targetMs position in milliseconds; the player clamps it to the video
     *
     * Return: nothing.
     */
    fun seekTo(targetMs: Long) {
        if (entry == null) return
        player?.seekTo(targetMs)
        positionMs = targetMs
    }

    /**
     * refreshPosition() - copies position and length from the player into the state
     *
     * The player does not announce every tick: whoever shows the clock calls
     * this while it is on screen, and nobody does while it is hidden.
     *
     * Return: nothing.
     */
    fun refreshPosition() {
        val exo = player ?: return
        if (entry == null) return
        positionMs = exo.currentPosition.coerceAtLeast(0)
        durationMs = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
    }

    /**
     * release() - frees the player for good, when the viewer closes
     *
     * Return: nothing.
     */
    fun release() {
        entry = null
        player?.run {
            removeListener(listener)
            release()
        }
        player = null
    }

    private fun resetProgress() {
        hasFrame = false
        isBuffering = false
        error = null
        positionMs = 0
        durationMs = 0
    }

    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .build()
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory(repository)))
            .setLoadControl(loadControl)
            // asks for the audio focus: the song of the music player stops while a video plays
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply { addListener(listener) }
    }

    /* Private: playbackError comes from the player, never null. */
    private fun messageFor(playbackError: PlaybackException): String = when (playbackError.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        -> "Il NAS non risponde: il video non si può leggere"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Il video non c'è più sul NAS"
        else -> "Questo video non si può riprodurre qui: prova «Apri l'originale»"
    }
}

/**
 * rememberVideoPlayerState() - the video player of one viewer
 *
 * The player pauses when the app leaves the screen and is freed when the
 * viewer closes.
 *
 * Context: composable.
 * Return: the same state for as long as the viewer stays open.
 */
@Composable
fun rememberVideoPlayerState(): VideoPlayerState {
    val context = LocalContext.current
    val state = remember(context) {
        val app = context.applicationContext as SmbCommanderApp
        VideoPlayerState(app, app.repository)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.pause() }
    DisposableEffect(state) {
        onDispose { state.release() }
    }
    return state
}
