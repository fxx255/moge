package com.moge.app.ui.capture

import android.view.Surface
import androidx.compose.ui.geometry.Offset
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
    @Test fun `guide follows the screen horizon sign in portrait and both landscape directions`() {
        // Each pair is the same positive or negative screen tilt in natural sensor coordinates.
        listOf(
            Triple(Offset(5f, 5f), Offset(-5f, 5f), Surface.ROTATION_0),
            Triple(Offset(-5f, 5f), Offset(-5f, -5f), Surface.ROTATION_90),
            Triple(Offset(-5f, -5f), Offset(5f, -5f), Surface.ROTATION_180),
            Triple(Offset(5f, -5f), Offset(5f, 5f), Surface.ROTATION_270),
        ).forEach { (positive, negative, rotation) ->
            val down = levelGuideOffset(screenRollDegrees(positive.x, positive.y, rotation)!!, 28f)
            val up = levelGuideOffset(screenRollDegrees(negative.x, negative.y, rotation)!!, 28f)
            assertEquals("right end X at rotation $rotation", 19.79899f, down.x, 0.0001f)
            assertEquals("right end drops for positive roll at rotation $rotation", 19.79899f, down.y, 0.0001f)
            assertEquals("opposite tilt keeps the horizontal projection", down.x, up.x, 0.0001f)
            assertEquals("right end rises for negative roll at rotation $rotation", -down.y, up.y, 0.0001f)
        }
    }

    @Test fun `guide stays centered and horizontal for a level phone in every rotation`() {
        listOf(
            Triple(0f, 9.8f, Surface.ROTATION_0),
            Triple(-9.8f, 0f, Surface.ROTATION_90),
            Triple(0f, -9.8f, Surface.ROTATION_180),
            Triple(9.8f, 0f, Surface.ROTATION_270),
        ).forEach { (x, y, rotation) ->
            val offset = levelGuideOffset(screenRollDegrees(x, y, rotation)!!, 28f)
            assertEquals(28f, offset.x, 0.0001f)
            assertEquals(0f, offset.y, 0.0001f)
        }
    }

    @Test fun `non finite gravity samples and insufficient screen projection are ignored`() {
        assertNull(screenRollDegrees(Float.POSITIVE_INFINITY, 9f, Surface.ROTATION_0))
        assertNull(screenRollDegrees(9f, Float.NEGATIVE_INFINITY, Surface.ROTATION_90))
        assertNull(screenRollDegrees(9f, Float.NaN, Surface.ROTATION_270))
        assertNull(screenRollDegrees(0f, 0f, Surface.ROTATION_180))
    }
}
