package com.moge.app.ui.capture

import android.view.Surface
import org.junit.Assert.*
import org.junit.Test

class LevelIndicatorTest {
    @Test fun `level is independent of four screen rotations`() {
        listOf(Triple(0f, 9.8f, Surface.ROTATION_0), Triple(-9.8f, 0f, Surface.ROTATION_90),
            Triple(0f, -9.8f, Surface.ROTATION_180), Triple(9.8f, 0f, Surface.ROTATION_270)).forEach { (x, y, r) ->
            assertEquals(0f, screenRollDegrees(x, y, r)!!, 0.01f)
        }
    }
    @Test fun `tilt changes direction and flat or invalid samples are ignored`() {
        assertEquals(45f, screenRollDegrees(5f, 5f, Surface.ROTATION_0)!!, 0.01f)
        assertEquals(-45f, screenRollDegrees(-5f, 5f, Surface.ROTATION_0)!!, 0.01f)
        assertNull(screenRollDegrees(0.01f, 0.03f, Surface.ROTATION_0))
        assertNull(screenRollDegrees(Float.NaN, 9f, Surface.ROTATION_0))
    }
}
