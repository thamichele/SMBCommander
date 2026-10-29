package io.github.thamichele.smbcommander

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import io.github.thamichele.smbcommander.data.CredentialStore
import io.github.thamichele.smbcommander.data.EditSessionStore
import io.github.thamichele.smbcommander.data.FolderStore
import io.github.thamichele.smbcommander.data.NasRepository
import io.github.thamichele.smbcommander.data.ThumbnailCache
import java.io.File

class SmbCommanderApp : Application(), SingletonImageLoader.Factory {

    lateinit var repository: NasRepository
        private set
    lateinit var editSessions: EditSessionStore
        private set
    lateinit var thumbnails: ThumbnailCache
        private set
    lateinit var folders: FolderStore
        private set

    override fun onCreate() {
        super.onCreate()
        repository = NasRepository(CredentialStore(this))
        editSessions = EditSessionStore(this)
        folders = FolderStore(this)
        thumbnails = ThumbnailCache(File(cacheDir, "thumbs"), repository)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(ThumbnailCache.Fetcher.Factory(thumbnails)) }
            .crossfade(true)
            .build()
}
