package io.github.thamichele.smbcommander.data

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.thamichele.smbcommander.MainActivity
import io.github.thamichele.smbcommander.SmbCommanderApp

/**
 * Plays music from the NAS in the background, streaming like the videos: the notification and the lock screen get
 * the usual controls, headphones unplugged pause it, and it keeps the Wi-Fi awake while it plays.
 */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as SmbCommanderApp
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(SmbDataSource.Factory(app.repository)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        val open = PendingIntent.getActivity(this, 1, MainActivity.open(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).setCallback(Callback()).build()
    }

    /**
     * Songs come only from this app: other apps (a watch, the car) may pause or skip, never queue files of the NAS.
     * The address of each song is rebuilt from its id, which is what crosses between the app and the service.
     */
    private inner class Callback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (controller.packageName != packageName) return Futures.immediateFuture(mutableListOf())
            return Futures.immediateFuture(
                mediaItems.mapNotNull { item ->
                    val id = item.mediaId
                    val share = id.substringBefore('/')
                    if (share.isEmpty()) null
                    else item.buildUpon().setUri(SmbDataSource.uri(share, id.substringAfter('/', ""))).build()
                }.toMutableList()
            )
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** The app swiped away while nothing plays: nothing to keep alive. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
