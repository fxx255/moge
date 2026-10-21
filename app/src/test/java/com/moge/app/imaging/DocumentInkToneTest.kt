package com.moge.app.imaging

import com.moge.app.imaging.book.DocumentInkTone
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Test

class DocumentInkToneTest {
    @Test fun `neutral lookup preserves every brightness and nearby ink combination`() {
        for (brightness in 0..255) for (nearbyMinimum in 0..255) {
            val gray = DocumentInkTone.value(brightness, nearbyMinimum, 0f).roundToInt().coerceIn(0, 255)
            val expected = (255 shl 24) or (gray shl 16) or (gray shl 8) or gray
            assertEquals(expected, DocumentInkTone.neutralArgb(brightness, nearbyMinimum))
        }
    }
}
