// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * VideoFramePlanTest.kt - tests of the moments of a video's animated preview
 *
 * Version: 1.27.0
 */
package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoFramePlanTest {

    @Test
    fun longVideoGetsStartTenSecondsAndMiddle() {
        assertEquals(listOf(2_000L, 10_000L, 300_000L), VideoFramePlan.positions(600_000))
    }

    @Test
    fun middleBeforeTenSecondsIsSortedFirst() {
        // 16 s: the middle (8 s) comes before the ten seconds
        assertEquals(listOf(2_000L, 8_000L, 10_000L), VideoFramePlan.positions(16_000))
    }

    @Test
    fun shortVideoKeepsFramesApartAndAwayFromTheEnd() {
        // 6 s: 2 s, middle 3 s (too close, dropped), 10 s moved to 5.5 s
        assertEquals(listOf(2_000L, 5_500L), VideoFramePlan.positions(6_000))
    }

    @Test
    fun veryShortVideoGetsOneFrame() {
        // 1.5 s: the middle (0.75 s) comes first, the others are too close to it
        assertEquals(listOf(750L), VideoFramePlan.positions(1_500))
    }

    @Test
    fun unknownLengthGetsOneFrame() {
        assertEquals(listOf(VideoFramePlan.EARLY_MS), VideoFramePlan.positions(0))
        assertEquals(listOf(VideoFramePlan.EARLY_MS), VideoFramePlan.positions(-1))
    }

    @Test
    fun neverMoreThanMaxFrames() {
        for (length in listOf(20_000L, 60_000L, 3_600_000L)) {
            val positions = VideoFramePlan.positions(length)
            assert(positions.size <= VideoFramePlan.MAX_FRAMES)
            assertEquals(positions.sorted(), positions)
        }
    }
}
