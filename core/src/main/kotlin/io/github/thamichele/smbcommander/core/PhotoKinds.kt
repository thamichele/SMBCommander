/*
 * PhotoKinds.kt - what a photo shows, worked out from the pixels of its thumbnail
 *
 * Tells screenshots, photos of text and ordinary photos apart with simple measures and no model, so it runs offline
 * on any phone. Counting faces is the caller's job (Android's own face detector), and so is decoding the thumbnail.
 * Raise VERSION whenever a threshold changes, so the photos already sorted are looked at again.
 *
 * Version: 1.28.6
 */
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

    /**
     * features() - measures a picture
     * @param width its width in pixels, more than 1
     * @param height its height in pixels, more than 1
     * @param argb the pixels row by row, as Android's Bitmap.getPixels gives them
     *
     * Return: the measures [kind] decides on.
     */
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
            lum[i] = (LUMA_R * r + LUMA_G * g + LUMA_B * b) shr LUMA_SHIFT
            val hi = max(r, max(g, b))
            val lo = min(r, min(g, b))
            if (hi > 0) satSum += (hi - lo).toFloat() / hi
            if (lo >= PURE_WHITE || hi <= PURE_BLACK) pure++
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

        val histogram = IntArray(LUM_BINS)
        for (l in lum) histogram[l shr LUM_BIN_SHIFT]++
        val mode = histogram.indices.maxBy { histogram[it] }
        var near = 0
        for (b in max(0, mode - NEAR_BINS)..min(LUM_BINS - 1, mode + NEAR_BINS)) near += histogram[b]

        return Features(
            ratio = height.toFloat() / width,
            flat = flat.toFloat() / n,
            topColors = colors.values.sortedDescending().take(TOP_COLORS).sum().toFloat() / n,
            pure = pure.toFloat() / n,
            saturation = satSum / n,
            background = near.toFloat() / n,
            edges = edges.toFloat() / n,
            blankRows = blank.toFloat() / height,
            busyRows = busy.toFloat() / height,
        )
    }

    /**
     * kind() - what a photo shows
     * @param f its measures
     * @param faces how many faces were found in it
     *
     * A screen is checked first (a chat screenshot is full of text and may show faces), then people, then text.
     *
     * Return: the group the quick cleanup puts it in.
     */
    fun kind(f: Features, faces: Int): PhotoKind = when {
        isScreen(f) -> PhotoKind.Screen
        faces > 0 -> PhotoKind.People
        isText(f) -> PhotoKind.Text
        else -> PhotoKind.Photo
    }

    /**
     * isScreen() - tells whether a picture is a screenshot
     * @param f its measures
     *
     * Return: true when enough signs of a phone screen add up: its shape, few colours, flat areas, pure white or black.
     */
    fun isScreen(f: Features): Boolean {
        // phones' screens are much longer than any camera format (4:3, 3:2, 16:9); panoramas are far wider still
        val long = max(f.ratio, 1 / f.ratio)
        var score = 0
        if (long in SCREEN_RATIO) score += SCREEN_RATIO_SCORE
        if (f.topColors >= FEW_COLORS) score++
        if (f.topColors >= VERY_FEW_COLORS) score++
        if (f.flat >= FLAT_SHARE) score++
        if (f.flat >= VERY_FLAT_SHARE) score++
        if (f.pure >= PURE_SHARE) score++
        return score >= SCREEN_SCORE
    }

    /**
     * isText() - tells whether a picture is a photo of text: a document, a receipt, a whiteboard
     * @param f its measures
     *
     * Return: true for a pale, even background crossed by rows of sharp changes with blank rows between them.
     */
    fun isText(f: Features): Boolean =
        f.saturation < TEXT_MAX_SATURATION &&
            f.background >= TEXT_MIN_BACKGROUND &&
            f.edges in TEXT_EDGES &&
            f.blankRows in TEXT_BLANK_ROWS &&
            f.busyRows >= TEXT_MIN_BUSY_ROWS

    /* Signs of a screenshot and how many points they must add up to. */
    private val SCREEN_RATIO = 1.95f..2.45f
    private const val SCREEN_RATIO_SCORE = 2
    private const val FEW_COLORS = 0.45f
    private const val VERY_FEW_COLORS = 0.65f
    private const val FLAT_SHARE = 0.55f
    private const val VERY_FLAT_SHARE = 0.75f
    private const val PURE_SHARE = 0.2f
    private const val SCREEN_SCORE = 4

    /* Signs of a photo of text. */
    private const val TEXT_MAX_SATURATION = 0.25f
    private const val TEXT_MIN_BACKGROUND = 0.4f
    private val TEXT_EDGES = 0.02f..0.3f
    private val TEXT_BLANK_ROWS = 0.1f..0.8f
    private const val TEXT_MIN_BUSY_ROWS = 0.1f

    /* Brightness as 0..255 from the channels, ITU-R BT.601 weights in 1/256. */
    private const val LUMA_R = 77
    private const val LUMA_G = 150
    private const val LUMA_B = 29
    private const val LUMA_SHIFT = 8
    private const val PURE_WHITE = 250
    private const val PURE_BLACK = 5

    /* Brightness histogram of 32 bins of 8 levels; the background is the top bin and three on each side. */
    private const val LUM_BINS = 32
    private const val LUM_BIN_SHIFT = 3
    private const val NEAR_BINS = 3
    private const val TOP_COLORS = 6

    private const val FLAT = 2
    private const val EDGE = 40
    private const val BLANK_ROW = 0.02f
    private const val BUSY_ROW = 0.06f
}
