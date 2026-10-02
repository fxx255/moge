package com.moge.app.ui.capture

import android.view.OrientationEventListener
import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraRotationTest {
    @Test
    fun `physical left and right landscape map to distinct CameraX rotations`() {
        assertEquals(Surface.ROTATION_0, cameraTargetRotation(0))
        assertEquals(Surface.ROTATION_270, cameraTargetRotation(90))
        assertEquals(Surface.ROTATION_180, cameraTargetRotation(180))
        assertEquals(Surface.ROTATION_90, cameraTargetRotation(270))
    }

    @Test
    fun `direction snaps at diagonal boundaries and wraps at north`() {
        listOf(
            44 to Surface.ROTATION_0, 45 to Surface.ROTATION_270,
            134 to Surface.ROTATION_270, 135 to Surface.ROTATION_180,
            224 to Surface.ROTATION_180, 225 to Surface.ROTATION_90,
            314 to Surface.ROTATION_90, 315 to Surface.ROTATION_0,
            359 to Surface.ROTATION_0,
        ).forEach { (degrees, rotation) -> assertEquals("$degrees°", rotation, cameraTargetRotation(degrees)) }
    }

    @Test
    fun `unknown or invalid orientation leaves the last direction intact`() {
        assertNull(cameraTargetRotation(OrientationEventListener.ORIENTATION_UNKNOWN))
        assertNull(cameraTargetRotation(360))
    }
}
