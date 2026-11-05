package com.moge.app.ui.capture

import org.junit.Assert.*
import org.junit.Test

class PaperExposureMeteringTest {
    @Test fun `shaded paper gets a stronger bounded bias and normal paper keeps a positive baseline`() {
        val levels = listOf(0, 40, 80, 120, 180, 220, 255)
        val biases = levels.map(::paperMeteringBiasEv)
        assertTrue(biases.zipWithNext().all { (dark, light) -> dark >= light })
        assertTrue(biases.all { it in PAPER_METERING_BIAS_EV..MAX_PAPER_METERING_BIAS_EV })
        assertEquals(MAX_PAPER_METERING_BIAS_EV, paperMeteringBiasEv(40), .001f)
        assertEquals(PAPER_METERING_BIAS_EV, paperMeteringBiasEv(220), .001f)
        assertEquals(PAPER_METERING_BIAS_EV, paperMeteringBiasEv(null), .001f)
        assertEquals(paperMeteringBiasEv(0), paperMeteringBiasEv(-1), 0f)
        assertEquals(paperMeteringBiasEv(255), paperMeteringBiasEv(300), 0f)
    }

    @Test fun `tap sampling measures shaded paper behind ink and rejects invalid coordinates`() {
        val width = 64; val height = 48
        val pixels = ByteArray(width * height) { 100 }
        for (y in 22..26) pixels[y * width + 32] = 10
        assertEquals(100, samplePaperLuminance(pixels, width, height, .5f, .5f))
        assertEquals(100, samplePaperLuminance(pixels, width, height, 0f, 0f))
        assertEquals(100, samplePaperLuminance(pixels, width, height, 1f, 1f))
        assertNull(samplePaperLuminance(pixels, width, height, -.1f, .5f))
        assertNull(samplePaperLuminance(pixels, width, height, .5f, Float.NaN))
        assertNull(samplePaperLuminance(byteArrayOf(), width, height, .5f, .5f))
    }

    @Test fun `paper recognition meters its shaded region while retaining its text focus point`() {
        val width = 64; val height = 48
        fun page(shaded: Boolean) = ByteArray(width * height) { 20 }.also { pixels ->
            for (y in 5..42) for (x in 8..55) pixels[y * width + x] = 220.toByte()
            if (shaded) for (y in 7..40) for (x in 16..31) pixels[y * width + x] = 100
            for (y in 10..36 step 5) for (x in 14..47 step 4) pixels[y * width + x] = 30
        }
        val normal = requireNotNull(detectPaperFocus(page(false), width, height))
        val shaded = requireNotNull(detectPaperFocus(page(true), width, height))
        assertTrue(shaded.x in .3f.. .7f)
        assertEquals(normal.y, shaded.y, .05f)
        val meter = requireNotNull(shaded.exposure)
        assertTrue("Meter the shaded paper, not the black desk or bright half", meter.x in .25f.. .5f)
        assertTrue("Keep focus on text independently of shadow metering", meter.x < shaded.x)
        assertEquals(100, meter.luminance)
        assertTrue(paperMeteringBiasEv(meter.luminance) > paperMeteringBiasEv(normal.exposure?.luminance))
    }
}
