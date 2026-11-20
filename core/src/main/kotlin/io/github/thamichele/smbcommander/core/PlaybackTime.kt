// SPDX-License-Identifier: LicenseRef-Proprietary
/*
 * PlaybackTime.kt - arithmetic of the video player's clock
 *
 * Pure functions for the labels and the seek bar of the player: formatting a
 * position, turning it into a fraction of the bar and back, clamping a jump.
 * No player, no Android: the screens pass plain milliseconds.
 *
 * Version: 1.21.0
 */
package io.github.thamichele.smbcommander.core

import java.util.Locale

/** Clock of the video player: positions in milliseconds, labels as "m:ss" or "h:mm:ss". */
object PlaybackTime {

    private const val MS_PER_SECOND = 1_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3_600L

    /**
     * format() - writes a position as "m:ss", or "h:mm:ss" from one hour on
     * @param positionMs position in milliseconds; negative counts as zero
     *
     * Seconds are cut, not rounded, so the label never shows a second that has
     * not started yet.
     *
     * Return: the label, never empty.
     */
    fun format(positionMs: Long): String {
        val total = positionMs.coerceAtLeast(0) / MS_PER_SECOND
        val hours = total / SECONDS_PER_HOUR
        val minutes = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
        val seconds = total % SECONDS_PER_MINUTE
        // Locale.ROOT: plain digits whatever the language of the phone
        if (hours == 0L) return String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        return String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * fraction() - how far along the seek bar a position is
     * @param positionMs position in milliseconds
     * @param durationMs length of the video; zero or less while still unknown
     *
     * Return: 0..1, and 0 while the length is unknown.
     */
    fun fraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0) return 0f
        return (positionMs.toDouble() / durationMs).toFloat().coerceIn(0f, 1f)
    }

    /**
     * positionAt() - the position a point of the seek bar stands for
     * @param fraction point of the bar, 0..1; values outside are clamped
     * @param durationMs length of the video; zero or less while still unknown
     *
     * Return: milliseconds inside 0..durationMs, and 0 while the length is unknown.
     */
    fun positionAt(fraction: Float, durationMs: Long): Long {
        if (durationMs <= 0) return 0
        return (fraction.coerceIn(0f, 1f).toDouble() * durationMs).toLong()
    }

    /**
     * seekTarget() - where a jump of deltaMs from positionMs lands
     * @param positionMs position now
     * @param deltaMs jump, negative to go back
     * @param durationMs length of the video; zero or less while still unknown
     *
     * The jump stops at the two ends of the video. While the length is unknown
     * only the start is a limit: the player clamps the rest by itself.
     *
     * Return: the new position in milliseconds, never negative.
     */
    fun seekTarget(positionMs: Long, deltaMs: Long, durationMs: Long): Long {
        val target = (positionMs + deltaMs).coerceAtLeast(0)
        if (durationMs <= 0) return target
        return target.coerceAtMost(durationMs)
    }
}
