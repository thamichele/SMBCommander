package io.github.thamichele.smbcommander.data

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Temporary copies of NAS files handed to other apps with "Condividi". They live only in the app's private cache
 * (never in a public folder, so neither the phone's gallery nor the photo backup ever sees them) and are deleted soon
 * after: when the user comes back to the app, and in any case by a cleanup that runs a few minutes later.
 */
class ShareCache(private val context: Context) {

    private val root = File(context.cacheDir, DIR)

    /** A new, empty folder for the copies of one share. */
    fun newFolder(): File = File(root, UUID.randomUUID().toString()).apply { mkdirs() }

    /**
     * Deletes the copies made more than [olderThanMs] ago. The receiving app reads the file while the user is in it:
     * a short grace period covers apps that read it a moment after the user came back.
     */
    fun clean(olderThanMs: Long) {
        val now = System.currentTimeMillis()
        root.listFiles()?.forEach { dir ->
            if (now - dir.lastModified() >= olderThanMs) dir.deleteRecursively()
        }
    }

    fun cleanAll() {
        root.deleteRecursively()
    }

    /** Makes sure the copies go away even if the app is never opened again. */
    fun scheduleCleanup() {
        val request = OneTimeWorkRequestBuilder<ShareCleanupWorker>()
            .setInitialDelay(CLEANUP_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        /** Under the cache path that the FileProvider already exposes (see res/xml/file_paths.xml). */
        const val DIR = "share"
        /** Back in the app: copies older than this are surely read by now. */
        const val GRACE_MS = 60_000L
        private const val CLEANUP_DELAY_MINUTES = 15L
        private const val WORK_NAME = "share-cleanup"
    }
}

/** The late cleanup of [ShareCache]: by now every app has read its copy. */
class ShareCleanupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        ShareCache(applicationContext).clean(olderThanMs = 10 * 60_000L)
        return Result.success()
    }
}
