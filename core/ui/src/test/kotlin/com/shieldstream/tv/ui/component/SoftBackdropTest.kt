package com.shieldstream.tv.ui.component

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftBackdropTest {
    private fun gray(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun `uniform image is unchanged`() {
        val pixels = IntArray(12 * 8) { gray(120) }
        val expected = pixels.copyOf()
        boxBlur(pixels, 12, 8, radius = 3)
        assertArrayEquals(expected, pixels)
    }

    @Test
    fun `hard edge becomes a gradient and keeps full alpha`() {
        val width = 20
        val height = 4
        val pixels = IntArray(width * height) { i -> if (i % width < width / 2) gray(0) else gray(255) }
        boxBlur(pixels, width, height, radius = 2)
        val row = (0 until width).map { pixels[it] and 0xFF }
        assertEquals(0, row.first())
        assertEquals(255, row.last())
        assertTrue("edge should soften", row[width / 2 - 1] in 1..254 && row[width / 2] in 1..254)
        assertTrue("monotonic across the edge", row.zipWithNext().all { (a, b) -> a <= b })
        assertTrue(pixels.all { it ushr 24 == 0xFF })
    }

    @Test
    fun `scrim alpha interpolates between stops and clamps outside them`() {
        val scrim = BackdropScrim(horizontal = true, stops = listOf(0f to 1f, 0.28f to 1f, 0.52f to 0.72f, 0.72f to 0f))
        assertEquals(1f, scrim.alphaAt(0f), 1e-6f)
        assertEquals(1f, scrim.alphaAt(0.2f), 1e-6f)
        assertEquals(0.86f, scrim.alphaAt(0.40f), 1e-4f)
        assertEquals(0.36f, scrim.alphaAt(0.62f), 1e-4f)
        assertEquals(0f, scrim.alphaAt(0.9f), 1e-6f)
        val vertical = BackdropScrim(horizontal = false, stops = listOf(0.40f to 0f, 0.72f to 0.25f, 1f to 1f))
        assertEquals(0f, vertical.alphaAt(0.1f), 1e-6f)
        assertEquals(1f, vertical.alphaAt(1f), 1e-6f)
    }

    @Test
    fun `baked scrims darken like gradients drawn over the image`() {
        val width = 10
        val height = 4
        val pixels = IntArray(width * height) { gray(200) }
        val scrims = listOf(
            BackdropScrim(horizontal = true, stops = listOf(0f to 1f, 1f to 0f)),
            BackdropScrim(horizontal = false, stops = listOf(0f to 0f, 1f to 0.5f)),
        )
        applyScrims(pixels, width, height, scrims, background = gray(0))
        fun valueAt(x: Int, y: Int) = pixels[y * width + x] and 0xFF
        // Black at alpha a over v leaves v * (1 - a); two scrims multiply.
        val expected = 200 * (1f - 0.95f) * (1f - 0.5f * 0.875f)
        assertEquals(expected, valueAt(0, 3).toFloat(), 1f)
        assertTrue("left edge is darker than right edge", valueAt(0, 0) < valueAt(width - 1, 0))
        assertTrue("bottom is darker than top", valueAt(5, height - 1) < valueAt(5, 0))
        assertTrue(pixels.all { it ushr 24 == 0xFF })
    }

    @Test
    fun `transparent pixels are flattened over the background`() {
        val pixels = intArrayOf(0x00FFFFFF, (0x80 shl 24) or 0xFFFFFF)
        applyScrims(pixels, 2, 1, emptyList(), background = gray(0))
        assertEquals(gray(0), pixels[0])
        assertEquals(128, pixels[1] and 0xFF)
        assertTrue(pixels.all { it ushr 24 == 0xFF })
    }

    @Test
    fun `zero radius is a no-op`() {
        val pixels = IntArray(9) { gray(it * 20) }
        val expected = pixels.copyOf()
        boxBlur(pixels, 3, 3, radius = 0)
        assertArrayEquals(expected, pixels)
    }
}
