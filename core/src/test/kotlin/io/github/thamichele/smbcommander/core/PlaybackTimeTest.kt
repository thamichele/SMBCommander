// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * PlaybackTimeTest.kt - tests of the video player's clock
 *
 * Version: 1.20.0
 */
package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimeTest {

    @Test
    fun formatsMinutesAndSecondsBelowOneHour() {
        assertEquals("0:00", PlaybackTime.format(0))
        assertEquals("0:09", PlaybackTime.format(9_999))
        assertEquals("12:05", PlaybackTime.format(725_000))
        assertEquals("59:59", PlaybackTime.format(3_599_999))
    }

    @Test
    fun formatsHoursFromOneHourOn() {
        assertEquals("1:00:00", PlaybackTime.format(3_600_000))
        assertEquals("2:03:04", PlaybackTime.format(7_384_000))
    }

    @Test
    fun formatTreatsNegativePositionAsZero() {
        assertEquals("0:00", PlaybackTime.format(-5_000))
    }

    @Test
    fun fractionIsZeroWhileDurationIsUnknown() {
        assertEquals(0f, PlaybackTime.fraction(10_000, 0), 0f)
        assertEquals(0f, PlaybackTime.fraction(10_000, -1), 0f)
    }

    @Test
    fun fractionStaysInsideTheBar() {
        assertEquals(0.25f, PlaybackTime.fraction(15_000, 60_000), 0.0001f)
        assertEquals(1f, PlaybackTime.fraction(90_000, 60_000), 0f)
        assertEquals(0f, PlaybackTime.fraction(-1, 60_000), 0f)
    }

    @Test
    fun positionAtIsTheInverseOfFraction() {
        assertEquals(15_000, PlaybackTime.positionAt(0.25f, 60_000))
        assertEquals(60_000, PlaybackTime.positionAt(1.5f, 60_000))
        assertEquals(0, PlaybackTime.positionAt(0.5f, 0))
    }

    @Test
    fun positionAtKeepsPrecisionOnLongVideos() {
        // three hours: a float alone would lose whole seconds here
        val durationMs = 3 * 3_600_000L
        assertEquals(durationMs / 2, PlaybackTime.positionAt(0.5f, durationMs))
    }

    @Test
    fun seekTargetStopsAtTheEnds() {
        assertEquals(0, PlaybackTime.seekTarget(4_000, -10_000, 60_000))
        assertEquals(60_000, PlaybackTime.seekTarget(55_000, 10_000, 60_000))
        assertEquals(25_000, PlaybackTime.seekTarget(15_000, 10_000, 60_000))
    }

    @Test
    fun seekTargetOnlyStopsAtTheStartWhileDurationIsUnknown() {
        assertEquals(0, PlaybackTime.seekTarget(4_000, -10_000, 0))
        assertEquals(14_000, PlaybackTime.seekTarget(4_000, 10_000, 0))
    }
}
