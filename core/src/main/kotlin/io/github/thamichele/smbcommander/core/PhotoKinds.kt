package io.github.thamichele.smbcommander.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** What a photo shows, worked out on the phone from its thumbnail: the groups of "all the others" in the quick cleanup. */
enum class PhotoKind(val label: String) {
    People("Persone"),
    Screen("Schermate"),
    Text("Scritte"),
    Photo("Altre foto"),
    Video("Video"),
}

/**
 * Tells screenshots, photos of text (documents, receipts, notes, whiteboards) and ordinary photos apart by looking at
 * the pixels of a thumbnail, with simple measures and no model: it runs offline on any phone and is right most of the
 * time, never always. Faces are counted by the caller (Android's own face detector) and passed to [kind].
 */
object PhotoKinds {

    /** Raised when the rules change, so photos already sorted are looked at again. */
    const val VERSION = 1

    data class Features(
        /** Height / width (upright). */
        val ratio: Float,
        /** Share of pixels equal to their right and lower neighbours: flat areas of an app, not a real scene. */
        val flat: Float,
        /** Share of pixels taken by the six most used colours. */
        val topColors: Float,
        /** Share of pure white or pure black pixels. */
        val pure: Float,
        /** Average colour saturation, 0..1. */
        val saturation: Float,
        /** Share of pixels close to the most common brightness: the paper, the board, the background. */
        val background: Float,
        /** Share of pixels on a sharp change of brightness. */
        val edges: Float,
        /** Share of rows with almost no sharp changes: the space between lines of text. */
        val blankRows: Float,
        /** Share of rows with many sharp changes: the lines of text. */
        val busyRows: Float,
    )

    /** Measures the picture [argb] (row by row, [width] x [height], as Android's Bitmap.getPixels gives it). */
    fun features(width: Int, height: Int, argb: IntArray): Features {
        require(width > 1 && height > 1 && argb.size >= width * height) { "Immagine vuota" }
        val n = width * height
        val lum = IntArray(n)
        var satSum = 0f
        var pure = 0
        val colors = HashMap<Int, Int>()
        for (i in 0 until n) {
            val p = argb[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            lum[i] = (77 * r + 150 * g + 29 * b) shr 8
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            if (hi > 0) satSum += (hi - lo).toFloat() / hi
            if (lo >= 250 || hi <= 5) pure++
            // 4 bits per channel: JPEG noise on a flat area stays in one bin
            val q = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            colors[q] = (colors[q] ?: 0) + 1
        }

        var flat = 0
        var edges = 0
        var blank = 0
        var busy = 0
        for (y in 0 until height) {
            var rowEdges = 0
            for (x in 0 until width) {
                val i = y * width + x
                val right = if (x + 1 < width) lum[i + 1] else lum[i]
                val below = if (y + 1 < height) lum[i + width] else lum[i]
                if (abs(lum[i] - right) <= FLAT && abs(lum[i] - below) <= FLAT) flat++
                if (abs(lum[i] - right) > EDGE) rowEdges++
            }
            edges += rowEdges
            val share = rowEdges.toFloat() / width
            if (share < BLANK_ROW) blank++ else if (share > BUSY_ROW) busy++
        }

        val histogram = IntArray(32)
        for (l in lum) histogram[l shr 3]++
        val mode = histogram.indices.maxBy { histogram[it] }
        var near = 0
        for (b in max(0, mode - 3)..min(31, mode + 3)) near += histogram[b]

        return Features(
            ratio = height.toFloat() / width,
            flat = flat.toFloat() / n,
            topColors = colors.values.sortedDescending().take(6).sum().toFloat() / n,
            pure = pure.toFloat() / n,
            saturation = satSum / n,
            background = near.toFloat() / n,
            edges = edges.toFloat() / n,
            blankRows = blank.toFloat() / height,
            busyRows = busy.toFloat() / height,
        )
    }

    /** A screen is checked first (a chat screenshot is full of text and may show faces), then people, then text. */
    fun kind(f: Features, faces: Int): PhotoKind = when {
        isScreen(f) -> PhotoKind.Screen
        faces > 0 -> PhotoKind.People
        isText(f) -> PhotoKind.Text
        else -> PhotoKind.Photo
    }

    fun isScreen(f: Features): Boolean {
        // phones' screens are much longer than any camera format (4:3, 3:2, 16:9); panoramas are far wider still
        val long = max(f.ratio, 1 / f.ratio)
        var score = 0
        if (long in 1.95f..2.45f) score += 2
        if (f.topColors >= 0.45f) score++
        if (f.topColors >= 0.65f) score++
        if (f.flat >= 0.55f) score++
        if (f.flat >= 0.75f) score++
        if (f.pure >= 0.2f) score++
        return score >= 4
    }

    fun isText(f: Features): Boolean =
        f.saturation < 0.25f &&
            f.background >= 0.4f &&
            f.edges in 0.02f..0.3f &&
            f.blankRows in 0.1f..0.8f &&
            f.busyRows >= 0.1f

    private const val FLAT = 2
    private const val EDGE = 40
    private const val BLANK_ROW = 0.02f
    private const val BUSY_ROW = 0.06f
}
