package com.moge.app.imaging

import com.moge.app.imaging.book.WarmPaperShadowModel
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class WarmPaperShadowModelTest {
    @Test fun `uniform warm paper and dark boards have no inferred shadow`() {
        for (color in listOf(listOf(240, 225, 205), listOf(60, 60, 60))) {
            val paper = ByteArray(3000) { color[it % 3].toByte() }
            assertNull(WarmPaperShadowModel.fit(paper))
        }
    }

    @Test fun `shadow follows paper attenuation while colored panels remain distinct`() {
        val paper = ByteArray(120 * 100 * 3)
        for (p in 0 until paper.size / 3) {
            val r = 120 + p / 100
            val shadow = ((220 - r) / 100f).coerceIn(0f, 1f)
            paper[p * 3] = r.toByte()
            paper[p * 3 + 1] = (r * (1 - .12f * shadow)).roundToInt().toByte()
            paper[p * 3 + 2] = (r * (1 - .28f * shadow)).roundToInt().toByte()
            // Printing at the same red brightness must not become the white reference.
            if (p % 10 == 0) {
                paper[p * 3 + 1] = (r * .7f).roundToInt().toByte()
                paper[p * 3 + 2] = (r * .6f).roundToInt().toByte()
            }
        }
        val model = requireNotNull(WarmPaperShadowModel.fit(paper))
        val inkGain = 1.08f
        val illumination = floatArrayOf(144f, 144f, 144f, inkGain)
        val expectedGreen = 144 * (1 - .12f * .76f)
        val expectedBlue = 144 * (1 - .28f * .76f)
        model.refine(byteArrayOf(144.toByte(), expectedGreen.roundToInt().toByte(), expectedBlue.roundToInt().toByte()), illumination)
        assertTrue(abs(illumination[1] - expectedGreen) < 2)
        assertTrue(abs(illumination[2] - expectedBlue) < 2)
        assertEquals(inkGain, illumination[3], 0f)
        val yellowInkBlue = 85f * 255 / illumination[2]
        assertTrue("Warm printing retains a visible color difference", 255 - yellowInkBlue > 40)
    }

    @Test fun `isolated colored marks do not establish a page shadow`() {
        val paper = ByteArray(30000) { 240.toByte() }
        for (p in 0 until 50) {
            paper[p * 3] = 125.toByte()
            paper[p * 3 + 1] = 110.toByte()
            paper[p * 3 + 2] = 90.toByte()
        }
        assertNull(WarmPaperShadowModel.fit(paper))
    }
}
