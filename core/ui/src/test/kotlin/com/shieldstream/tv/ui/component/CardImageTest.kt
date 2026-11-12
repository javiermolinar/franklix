package com.shieldstream.tv.ui.component

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardImageTest {
    @Test
    fun `same aspect ratio uses the whole image`() {
        assertEquals(IntOffset(0, 0) to IntSize(342, 513), centreCropSource(342, 513, 272f, 408f))
    }

    @Test
    fun `wider image is cropped at both sides`() {
        // 16:9 into a 2:3 box keeps the full height and the centred 2:3 slice of the width.
        assertEquals(IntOffset(160, 0) to IntSize(120, 180), centreCropSource(440, 180, 200f, 300f))
    }

    @Test
    fun `taller image is cropped at top and bottom`() {
        assertEquals(IntOffset(0, 25) to IntSize(100, 150), centreCropSource(100, 200, 272f, 408f))
    }

    @Test
    fun `empty image or box draws nothing`() {
        assertNull(centreCropSource(0, 100, 10f, 10f))
        assertNull(centreCropSource(100, 100, 0f, 10f))
    }
}
