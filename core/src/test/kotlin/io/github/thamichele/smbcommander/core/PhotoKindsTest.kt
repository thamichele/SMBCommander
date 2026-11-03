package io.github.thamichele.smbcommander.core

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class PhotoKindsTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun kindOf(width: Int, height: Int, faces: Int = 0, pixel: (Int, Int) -> Int): PhotoKind {
        val pixels = IntArray(width * height) { pixel(it % width, it / width) }
        return PhotoKinds.kind(PhotoKinds.features(width, height, pixels), faces)
    }

    /** A chat app: status bar, coloured header, bubbles with lines of text on a flat background. */
    private fun chat(x: Int, y: Int): Int = when {
        y < 12 -> rgb(0, 0, 0)
        y < 40 -> rgb(18, 140, 126)
        y % 50 in 10..34 && x in 20..140 -> if (y % 50 in 16..19 && x % 6 < 3) rgb(20, 20, 20) else rgb(220, 248, 198)
        else -> rgb(255, 255, 255)
    }

    @Test
    fun aPhoneScreenIsAScreen() {
        assertEquals(PhotoKind.Screen, kindOf(180, 390, pixel = ::chat))
        // even with a face in it
        assertEquals(PhotoKind.Screen, kindOf(180, 390, faces = 1, pixel = ::chat))
    }

    @Test
    fun aPageOfTextIsText() {
        val noise = Random(1)
        val kind = kindOf(300, 400) { x, y ->
            val paper = 215 + x / 30 + noise.nextInt(-6, 7)
            val ink = y % 22 in 6..13 && x in 20..280 && (x / 4 + y / 22) % 3 != 0 && x % 4 < 2
            if (ink) rgb(paper - 170, paper - 170, paper - 160) else rgb(paper, paper - 2, paper - 8)
        }
        assertEquals(PhotoKind.Text, kind)
    }

    @Test
    fun aSceneIsAPhoto() {
        val noise = Random(2)
        val kind = kindOf(384, 512) { x, y ->
            val n = noise.nextInt(-12, 13)
            if (y < 200) rgb(90 + y / 4 + n, 140 + y / 5 + n, 220 + n) else rgb(60 + x / 8 + n, 120 + (x * y) % 40 + n, 40 + n)
        }
        assertEquals(PhotoKind.Photo, kind)
    }

    @Test
    fun facesMakeAPhotoOfPeople() {
        val noise = Random(3)
        val kind = kindOf(384, 512, faces = 2) { x, y ->
            val n = noise.nextInt(-12, 13)
            rgb(180 + n - y / 10, 140 + n + x / 20, 120 + n)
        }
        assertEquals(PhotoKind.People, kind)
    }
}
