package com.moge.app.ui.capture

import android.app.Application
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.compose.ui.geometry.Offset
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
class CameraPaperFocusTest {
    private val queue = ArrayDeque<Runnable>()
    private val preview = mockk<PreviewView>(relaxed = true)
    private val focused = mutableListOf<Offset>()
    private var allowFocus = true
    private val focus = CameraPaperFocus(preview, Executor(queue::addLast), { allowFocus }, { point, _, _ -> focused += point })

    private fun image(): ImageProxy {
        val width = 64; val height = 48
        val pixels = ByteArray(width * height) { 45 }
        for (y in 5..42) for (x in 8..55) pixels[y * width + x] = 220.toByte()
        for (y in 10..36 step 5) for (x in 14..47 step 4) pixels[y * width + x] = 30
        val plane = mockk<ImageProxy.PlaneProxy> {
            every { buffer } returns ByteBuffer.wrap(pixels)
            every { rowStride } returns width
            every { pixelStride } returns 1
        }
        return mockk<ImageProxy>(relaxed = true) {
            every { getWidth() } returns width
            every { getHeight() } returns height
            every { cropRect } returns Rect(0, 0, width, height)
            every { planes } returns arrayOf(plane)
        }
    }

    private fun sample() = image().also {
        ShadowSystemClock.advanceBy(Duration.ofMillis(500))
        focus.analyze(it)
        verify(exactly = 1) { it.close() }
    }

    @Test fun `stable sampled paper is mapped to preview focus while unchanged frames do not refocus`() {
        try {
            every { preview.width } returns 64
            every { preview.height } returns 48
            every { preview.outputTransform } returns ImageProxyTransformFactory().getOutputTransform(image())
            repeat(7) { sample(); while (queue.isNotEmpty()) queue.removeFirst().run() }
            assertEquals(1, focused.size)
            assertTrue(focused.single().x in 15f..49f && focused.single().y in 10f..38f)
        } finally { focus.close() }
    }

    @Test fun `capture pause and disposal discard queued detections and always close frames`() {
        try {
            sample()
            assertTrue(queue.isNotEmpty())
            focus.suppress()
            while (queue.isNotEmpty()) queue.removeFirst().run()
            sample()
            focus.reset()
            while (queue.isNotEmpty()) queue.removeFirst().run()
            sample()
            focus.close()
            while (queue.isNotEmpty()) queue.removeFirst().run()
            assertTrue(focused.isEmpty())
            sample()
            assertTrue(queue.isEmpty())
            val broken = mockk<ImageProxy> { every { cropRect } throws IllegalStateException("closed camera") }
            every { broken.close() } returns Unit
            focus.analyze(broken)
            verify(exactly = 1) { broken.close() }
        } finally { focus.close() }
    }

    @Test fun `manual metering samples paper through the preview transform while autofocus is suppressed`() {
        try {
            every { preview.width } returns 64
            every { preview.height } returns 48
            every { preview.outputTransform } returns ImageProxyTransformFactory().getOutputTransform(image())
            allowFocus = false
            assertNull(focus.luminanceAt(Offset(32f, 24f)))
            sample()
            while (queue.isNotEmpty()) queue.removeFirst().run()
            assertTrue(focused.isEmpty())
            assertEquals(220, focus.luminanceAt(Offset(32f, 24f)))
            assertEquals(45, focus.luminanceAt(Offset(2f, 2f)))
            assertNull(focus.luminanceAt(Offset(-20f, 24f)))
            ShadowSystemClock.advanceBy(Duration.ofMillis(1_300))
            assertNull(focus.luminanceAt(Offset(32f, 24f)))
            sample()
            while (queue.isNotEmpty()) queue.removeFirst().run()
            focus.suppress()
            assertNull(focus.luminanceAt(Offset(32f, 24f)))
            sample()
            focus.reset()
            while (queue.isNotEmpty()) queue.removeFirst().run()
            assertNull(focus.luminanceAt(Offset(32f, 24f)))
        } finally { focus.close() }
    }
}
