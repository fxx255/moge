package com.moge.app.ui.capture

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.compose.ui.geometry.Offset
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CameraPreviewGesturesTest {
    private val view = View(ApplicationProvider.getApplicationContext<Application>()).apply { layout(0, 0, 400, 600) }
    private val taps = mutableListOf<Offset>()
    private val scales = mutableListOf<Float>()
    private var zoomStarts = 0
    private var time = SystemClock.uptimeMillis()

    private fun event(action: Int, x: Float, y: Float, down: Long) {
        val event = MotionEvent.obtain(down, time, action, x, y, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        time += 40
    }

    private fun tap(x: Float, y: Float) {
        val down = time
        event(MotionEvent.ACTION_DOWN, x, y, down)
        event(MotionEvent.ACTION_UP, x, y, down)
    }

    private fun install() = installCameraPreviewGestures(view, "对焦并测光", taps::add, { zoomStarts++ }, scales::add)

    private fun pointers(action: Int, down: Long, first: Offset, second: Offset? = null) {
        val points = listOfNotNull(first, second)
        val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
            x = points[index].x; y = points[index].y; pressure = 1f; size = 1f
        } }
        val event = MotionEvent.obtain(down, time, action, points.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        time += 40
    }

    @Test fun `touches keep their actual coordinates and quick successive taps are both honored`() {
        install()
        tap(60f, 170f)
        tap(280f, 340f)
        assertEquals(listOf(Offset(60f, 170f), Offset(280f, 340f)), taps)
    }

    @Test fun `drags and cancelled gestures do not cause a center or endpoint focus request`() {
        install()
        val down = time
        event(MotionEvent.ACTION_DOWN, 30f, 70f, down)
        event(MotionEvent.ACTION_MOVE, 260f, 340f, down)
        event(MotionEvent.ACTION_UP, 260f, 340f, down)
        val cancelled = time
        event(MotionEvent.ACTION_DOWN, 30f, 70f, cancelled)
        event(MotionEvent.ACTION_CANCEL, 30f, 70f, cancelled)
        assertTrue(taps.isEmpty())
    }

    @Test fun `accessibility click focuses the center and cleanup removes the old action`() {
        val uninstall = install()
        assertEquals("对焦并测光", view.contentDescription)
        assertTrue(view.performClick())
        assertEquals(listOf(Offset(200f, 300f)), taps)
        uninstall()
        assertFalse(view.performClick())
        assertNull(view.contentDescription)
        assertEquals(1, taps.size)
    }

    @Test fun `pinching scales the camera without focusing when either finger lifts`() {
        install()
        val down = time
        pointers(MotionEvent.ACTION_DOWN, down, Offset(150f, 300f))
        pointers(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down,
            Offset(150f, 300f), Offset(250f, 300f))
        pointers(MotionEvent.ACTION_MOVE, down, Offset(100f, 300f), Offset(300f, 300f))
        pointers(MotionEvent.ACTION_MOVE, down, Offset(50f, 300f), Offset(350f, 300f))
        pointers(MotionEvent.ACTION_MOVE, down, Offset(100f, 300f), Offset(300f, 300f))
        pointers(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down,
            Offset(100f, 300f), Offset(300f, 300f))
        pointers(MotionEvent.ACTION_UP, down, Offset(100f, 300f))
        assertEquals(1, zoomStarts)
        assertTrue(scales.any { it > 1f })
        assertTrue(scales.any { it < 1f })
        assertTrue(taps.isEmpty())
        tap(70f, 100f)
        assertEquals(listOf(Offset(70f, 100f)), taps)
    }

    @Test fun `two finger taps and cancelled pinches never fall through to tap focus`() {
        install()
        for (cancel in listOf(false, true)) {
            val down = time
            pointers(MotionEvent.ACTION_DOWN, down, Offset(150f, 300f))
            pointers(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down,
                Offset(150f, 300f), Offset(250f, 300f))
            if (cancel) pointers(MotionEvent.ACTION_CANCEL, down, Offset(150f, 300f), Offset(250f, 300f))
            else {
                pointers(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), down,
                    Offset(150f, 300f), Offset(250f, 300f))
                pointers(MotionEvent.ACTION_UP, down, Offset(150f, 300f))
            }
        }
        assertTrue(taps.isEmpty())
        assertTrue(scales.isEmpty())
    }
}
