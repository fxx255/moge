package com.moge.app.ui.capture

import android.app.Application
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.ZoomState
import androidx.lifecycle.MutableLiveData
import com.google.common.util.concurrent.SettableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CameraPreviewZoomTest {
    private val camera = mockk<Camera>(relaxed = true)
    private val control = mockk<CameraControl>(relaxed = true)
    private val ratios = mutableListOf<Float>()
    private val requests = mutableListOf<SettableFuture<Void>>()
    private val state = MutableLiveData(zoomState(1f))
    private val zoom = CameraPreviewZoom(Executor { it.run() })

    private fun zoomState(ratio: Float, minimum: Float = 1f, maximum: Float = 10f) = mockk<ZoomState> {
        every { zoomRatio } returns ratio
        every { minZoomRatio } returns minimum
        every { maxZoomRatio } returns maximum
    }

    private fun attach() {
        every { camera.cameraControl } returns control
        every { camera.cameraInfo.zoomState } returns state
        every { control.setZoomRatio(capture(ratios)) } answers {
            SettableFuture.create<Void>().also(requests::add)
        }
        zoom.attach(camera)
    }

    @Test fun `pinch deltas accumulate even when the camera state and previous request have not caught up`() {
        attach()
        zoom.scaleBy(1.5f)
        zoom.scaleBy(1.2f)
        assertTrue(requests.first().isCancelled)
        assertEquals(1.8f, ratios.last(), .0001f)
        zoom.beginGesture()
        zoom.scaleBy(1.1f)
        assertEquals(1.98f, ratios.last(), .0001f)
        assertEquals(1f, state.value!!.zoomRatio, .0001f)
    }

    @Test fun `zoom clamps at one and three and immediately reverses after reaching either limit`() {
        attach()
        for (factor in listOf(100f, .5f, .001f, 1.1f)) zoom.scaleBy(factor)
        assertEquals(listOf(3f, 1.5f, 1f, 1.1f), ratios)
        val count = ratios.size
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) zoom.scaleBy(invalid)
        assertEquals(count, ratios.size)
    }

    @Test fun `hardware limits are respected and a camera without zoom stays usable`() {
        attach()
        state.value = zoomState(1f, .5f, 2f)
        zoom.scaleBy(10f)
        assertEquals(2f, ratios.last(), .0001f)
        zoom.scaleBy(.01f)
        assertEquals(1f, ratios.last(), .0001f)
        state.value = zoomState(1f, 1f, 1f)
        val count = ratios.size
        zoom.scaleBy(2f)
        assertEquals(count, ratios.size)
    }

    @Test fun `failed requests recover from actual camera state and the next gesture still zooms`() {
        attach()
        zoom.scaleBy(2f)
        state.value = zoomState(1.5f)
        requests.last().setException(IllegalStateException("camera closing"))
        zoom.scaleBy(1.2f)
        assertEquals(1.8f, ratios.last(), .0001f)
        every { control.setZoomRatio(any()) } throws IllegalStateException("camera closed")
        zoom.scaleBy(1.2f)
        every { control.setZoomRatio(capture(ratios)) } answers { SettableFuture.create<Void>().also(requests::add) }
        zoom.scaleBy(1.2f)
        assertEquals(1.8f, ratios.last(), .0001f)
    }

    @Test fun `pause discards stale requests and an old binding cannot detach its replacement`() {
        attach()
        zoom.scaleBy(3f)
        zoom.reset()
        assertTrue(requests.last().isCancelled)
        state.value = zoomState(1f)
        zoom.beginGesture()
        zoom.scaleBy(1.1f)
        assertEquals(1.1f, ratios.last(), .0001f)
        val replacement = mockk<Camera>(relaxed = true)
        val replacementControl = mockk<CameraControl>(relaxed = true)
        every { replacement.cameraInfo.zoomState } returns MutableLiveData(zoomState(1f))
        every { replacement.cameraControl } returns replacementControl
        val request = SettableFuture.create<Void>()
        every { replacementControl.setZoomRatio(any()) } returns request
        zoom.attach(replacement)
        zoom.detach(camera)
        zoom.scaleBy(1.5f)
        verify(exactly = 1) { replacementControl.setZoomRatio(1.5f) }
        assertFalse(request.isCancelled)
        zoom.detach(replacement)
        assertTrue(request.isCancelled)
        zoom.scaleBy(2f)
        verify(exactly = 1) { replacementControl.setZoomRatio(any()) }
    }
}
