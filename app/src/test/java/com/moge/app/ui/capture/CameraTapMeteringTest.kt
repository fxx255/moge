package com.moge.app.ui.capture

import android.app.Application
import android.graphics.PointF
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.compose.ui.geometry.Offset
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
class CameraTapMeteringTest {
    private val camera = mockk<Camera>(relaxed = true)
    private val control = mockk<CameraControl>(relaxed = true)
    private val actions = mutableListOf<FocusMeteringAction>()
    private val requests = mutableListOf<SettableFuture<FocusMeteringResult>>()
    private val metering = CameraTapMetering(Executor { it.run() })
    private val factory = SurfaceOrientedMeteringPointFactory(400f, 600f)

    private fun attach() {
        every { camera.cameraControl } returns control
        every { camera.cameraInfo.isFocusMeteringSupported(any()) } answers {
            firstArg<FocusMeteringAction>().meteringPointsAe.any { it.x in 0f..1f && it.y in 0f..1f }
        }
        every { control.startFocusAndMetering(capture(actions)) } answers {
            SettableFuture.create<FocusMeteringResult>().also(requests::add)
        }
        metering.attach(camera)
    }

    @Test fun `a tap meters exposure and focus at the same point and restores continuous metering after five seconds`() {
        attach()
        assertTrue(metering.focusAt(factory, Offset(100f, 450f)))
        val action = actions.single()
        assertSame(action.meteringPointsAf.single(), action.meteringPointsAe.single())
        assertEquals(.25f, action.meteringPointsAf.single().x, .001f)
        assertEquals(.75f, action.meteringPointsAe.single().y, .001f)
        assertTrue(action.meteringPointsAwb.isEmpty())
        assertTrue(action.isAutoCancelEnabled)
        assertEquals(5_000L, action.autoCancelDurationInMillis)
        assertEquals(Offset(100f, 450f), metering.target!!.position)
    }

    @Test fun `unready previews invalid transforms and black bars do not submit camera requests`() {
        assertFalse(metering.focusAt(factory, Offset(200f, 300f)))
        attach()
        for (point in listOf(Offset(-1f, 10f), Offset(401f, 10f), Offset(20f, 601f), Offset(Float.NaN, 20f))) {
            assertFalse(metering.focusAt(factory, point))
        }
        val unreadyFactory = object : MeteringPointFactory() {
            override fun convertPoint(x: Float, y: Float) = PointF(-1f, -1f)
        }
        assertFalse(metering.focusAt(unreadyFactory, Offset(200f, 300f)))
        assertNull(metering.target)
        assertTrue(actions.isEmpty())
    }

    @Test fun `a repeated tap supersedes the old future and an old indicator timeout cannot clear its replacement`() {
        attach()
        metering.focusAt(factory, Offset(100f, 200f))
        val first = metering.target!!
        metering.focusAt(factory, Offset(300f, 400f))
        assertTrue(requests.first().isCancelled)
        val latest = metering.target!!
        metering.dismiss(first.id)
        assertEquals(latest, metering.target)
        requests.last().set(mockk(relaxed = true))
        assertEquals(latest, metering.target)
        metering.dismiss(latest.id)
        assertNull(metering.target)
        verify(exactly = 0) { control.cancelFocusAndMetering() }
    }

    @Test fun `fixed focus cameras can still apply exposure metering without claiming focus success`() {
        attach()
        every { camera.cameraInfo.isFocusMeteringSupported(any()) } answers {
            firstArg<FocusMeteringAction>().meteringPointsAe.isNotEmpty()
        }
        metering.focusAt(factory, Offset(100f, 200f))
        val result = mockk<FocusMeteringResult> { every { isFocusSuccessful } returns false }
        requests.single().set(result)
        assertNotNull(metering.target)
        assertEquals(1, actions.size)
    }

    @Test fun `unsupported or closing cameras do not break the preview`() {
        attach()
        every { camera.cameraInfo.isFocusMeteringSupported(any()) } returns false
        assertFalse(metering.focusAt(factory, Offset(100f, 200f)))
        assertNull(metering.target)
        assertTrue(actions.isEmpty())
        every { camera.cameraInfo.isFocusMeteringSupported(any()) } returns true
        every { control.startFocusAndMetering(any()) } throws IllegalStateException("camera closed")
        assertTrue(metering.focusAt(factory, Offset(300f, 400f)))
        assertEquals(Offset(300f, 400f), metering.target!!.position)
    }

    @Test fun `pause or rotation clears the indicator cancels pending work and returns to automatic metering`() {
        attach()
        metering.focusAt(factory, Offset(100f, 200f))
        metering.reset()
        assertNull(metering.target)
        assertTrue(requests.single().isCancelled)
        verify(exactly = 1) { control.cancelFocusAndMetering() }
        assertTrue(metering.focusAt(factory, Offset(200f, 300f)))
    }

    @Test fun `disposing an older binding cannot cancel the next camera`() {
        attach()
        metering.focusAt(factory, Offset(100f, 200f))
        val replacement = mockk<Camera>(relaxed = true)
        val replacementControl = mockk<CameraControl>(relaxed = true)
        every { replacement.cameraControl } returns replacementControl
        every { replacement.cameraInfo.isFocusMeteringSupported(any()) } returns true
        val request = SettableFuture.create<FocusMeteringResult>()
        every { replacementControl.startFocusAndMetering(any()) } returns request
        metering.attach(replacement)
        metering.focusAt(factory, Offset(300f, 400f))
        val latest = metering.target
        metering.detach(camera)
        assertEquals(latest, metering.target)
        assertFalse(request.isCancelled)
        verify(exactly = 0) { replacementControl.cancelFocusAndMetering() }
        metering.detach(replacement)
        assertTrue(request.isCancelled)
        assertNull(metering.target)
        assertFalse(metering.focusAt(factory, Offset(100f, 200f)))
    }
}
