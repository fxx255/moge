package com.moge.app.ui.capture

import android.app.Application
import android.util.Range
import android.util.Rational
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.ExposureState
import com.google.common.util.concurrent.SettableFuture
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CameraExposureControlTest {
    private val camera = mockk<Camera>(relaxed = true)
    private val control = mockk<CameraControl>(relaxed = true)
    private val state = mockk<ExposureState>()
    private val requests = mutableListOf<SettableFuture<Int>>()
    private val indices = mutableListOf<Int>()
    private val exposure = CameraExposureControl(Executor { it.run() })

    private fun attach(min: Int = -6, max: Int = 6, supported: Boolean = true) {
        every { camera.cameraControl } returns control
        every { camera.cameraInfo.exposureState } returns state
        every { state.isExposureCompensationSupported } returns supported
        every { state.exposureCompensationRange } returns Range(min, max)
        every { state.exposureCompensationIndex } returns 0
        every { state.exposureCompensationStep } returns Rational(1, 3)
        every { control.setExposureCompensationIndex(capture(indices)) } answers {
            SettableFuture.create<Int>().also(requests::add)
        }
        exposure.attach(camera)
    }

    @Test fun `slider is linear across the hardware range including asymmetric neutral position`() {
        attach(-3, 9)
        assertEquals(.25f, exposure.fraction, .001f)
        for (fraction in listOf(0f, .25f, .5f, .75f, 1f)) exposure.adjustTo(fraction)
        assertEquals(listOf(-3, 0, 3, 6, 9), indices)
        assertEquals(1f, exposure.fraction, 0f)
    }

    @Test fun `tap metering gets a small positive bias without accumulating or replacing manual brightness`() {
        attach()
        repeat(3) { exposure.applyMeteringDefault() }
        assertEquals(listOf(1), indices)
        assertEquals(7f / 12f, exposure.fraction, .001f)
        exposure.adjustTo(.25f)
        exposure.applyMeteringDefault()
        assertEquals(listOf(1, -3), indices)
        exposure.reset()
        exposure.refresh()
        exposure.applyMeteringDefault()
        assertEquals("Pausing does not erase the manual choice", listOf(1, -3), indices)
    }

    @Test fun `metering bias respects actual EV steps hardware limits and unsupported compensation`() {
        attach(-3, 9)
        every { state.exposureCompensationStep } returns Rational(1, 6)
        exposure.applyMeteringDefault()
        assertEquals(listOf(2), indices)
        exposure.detach(camera)
        attach(-1, 1)
        exposure.applyMeteringDefault()
        assertEquals(1, indices.last())
        exposure.detach(camera)
        attach(-3, 0)
        exposure.applyMeteringDefault()
        assertEquals(2, indices.size)
        exposure.detach(camera)
        attach(supported = false)
        exposure.applyMeteringDefault()
        assertEquals(2, indices.size)
        exposure.detach(camera)
        attach()
        every { state.exposureCompensationStep } returns Rational.ZERO
        exposure.applyMeteringDefault()
        assertEquals(2, indices.size)
        every { state.exposureCompensationStep } returns Rational(1, 1)
        exposure.applyMeteringDefault()
        assertEquals("A coarse camera step must not over-brighten the image", 2, indices.size)
    }

    @Test fun `unsupported and invalid input do not submit requests and fractional movement is retained between steps`() {
        attach(supported = false)
        exposure.adjustTo(.75f)
        assertTrue(indices.isEmpty())
        every { state.isExposureCompensationSupported } returns true
        exposure.refresh()
        exposure.adjustTo(Float.NaN)
        exposure.adjustTo(Float.POSITIVE_INFINITY)
        exposure.adjustTo(.51f)
        assertTrue(indices.isEmpty())
        assertEquals(.51f, exposure.fraction, 0f)
        exposure.adjustTo(2f)
        exposure.adjustTo(3f)
        assertEquals(listOf(6), indices)
    }

    @Test fun `a stale request or old binding cannot change the next camera`() {
        attach()
        exposure.adjustTo(.75f)
        exposure.adjustTo(.25f)
        assertTrue(requests.first().isCancelled)
        val replacement = mockk<Camera>(relaxed = true)
        every { replacement.cameraInfo.exposureState } returns state
        every { replacement.cameraControl } returns control
        exposure.attach(replacement)
        assertTrue(requests[1].isCancelled)
        exposure.adjustTo(1f)
        exposure.detach(camera)
        assertFalse(requests.last().isCancelled)
        assertEquals(1f, exposure.fraction, 0f)
        requests.last().set(6)
        assertEquals(1f, exposure.fraction, 0f)
        exposure.detach(replacement)
        assertFalse(exposure.supported)
        exposure.adjustTo(0f)
        assertEquals(listOf(3, -3, 6), indices)
    }

    @Test fun `failed updates restore the actual camera position and pause cancels pending work`() {
        attach()
        exposure.adjustTo(.8f)
        requests.last().setException(IllegalStateException("camera closed"))
        assertEquals(.5f, exposure.fraction, 0f)
        exposure.adjustTo(.2f)
        exposure.reset()
        assertTrue(requests.last().isCancelled)
        every { state.exposureCompensationIndex } returns 3
        exposure.refresh()
        assertEquals(.75f, exposure.fraction, 0f)
    }
}
