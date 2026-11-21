// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * VideoFramePlan.kt - where in a video the frames of its animated preview are taken
 *
 * Pure arithmetic: given the length of a video, it picks two or three moments
 * far enough apart to show different scenes (just after the start, around
 * ten seconds in, the middle). It reads nothing: the app passes the length
 * the decoder reported and extracts the frames itself.
 *
 * Version: 1.27.0
 */
package io.github.thamichele.smbcommander.core

/** The moments of a video shown in turn by its preview in the lists. */
object VideoFramePlan {

    /** Just after the start: the very first frames are often black or a fade in. */
    const val EARLY_MS = 2_000L

    /** Around ten seconds in, as Michele asked: usually a different scene from the start. */
    const val LATER_MS = 10_000L

    /* The middle of the video, as a fraction of its length. */
    private const val MIDDLE_FRACTION = 0.5

    /* Two frames closer than this would look the same. */
    const val MIN_GAP_MS = 1_500L

    /* The last instants are often a fade out: no frame is taken this close to the end. */
    private const val END_MARGIN_MS = 500L

    /** Never more frames than this: each one is a read on the NAS and a file in the cache. */
    const val MAX_FRAMES = 3

    /**
     * positions() - the moments of the frames of a video's animated preview
     * @param durationMs length of the video in milliseconds; zero or less when unknown
     *
     * The moments are sorted and at least MIN_GAP_MS apart, so a short video
     * gets fewer frames: a clip of three seconds gets one, which means no
     * animation at all.
     *
     * Return: one to MAX_FRAMES moments in milliseconds, the first one always present.
     */
    fun positions(durationMs: Long): List<Long> {
        if (durationMs <= 0) return listOf(EARLY_MS)
        val last = (durationMs - END_MARGIN_MS).coerceAtLeast(0)
        val wanted = listOf(EARLY_MS, LATER_MS, (durationMs * MIDDLE_FRACTION).toLong())
        val sorted = wanted.map { it.coerceAtMost(last) }.sorted()
        val picked = ArrayList<Long>(MAX_FRAMES)
        for (at in sorted) {
            if (picked.isEmpty() || at - picked.last() >= MIN_GAP_MS) picked += at
        }
        return picked.take(MAX_FRAMES)
    }
}
