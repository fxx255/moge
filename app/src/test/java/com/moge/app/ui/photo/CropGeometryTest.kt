package com.moge.app.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class CropGeometryTest {

    @Test
    fun `portrait preview keeps cover transform when mapping crop`() {
        val crop = cropBoundsInSource(
            previewSize = IntSize(1000, 2000),
            sourceSize = IntSize(3000, 6000),
            viewport = IntSize(1000, 1000),
            zoom = 1f,
            imageOffset = Offset.Zero,
            cropRect = Rect(0f, 250f, 1000f, 750f),
        )

        assertEquals(PixelCrop(0, 2250, 3000, 1500), crop)
    }

    @Test
    fun `zoom and pan are included in source mapping`() {
        val crop = cropBoundsInSource(
            previewSize = IntSize(1000, 1000),
            sourceSize = IntSize(4000, 4000),
            viewport = IntSize(1000, 1000),
            zoom = 2f,
            imageOffset = Offset(100f, -50f),
            cropRect = Rect(250f, 250f, 750f, 750f),
        )

        assertEquals(PixelCrop(1300, 1600, 1000, 1000), crop)
    }

    @Test
    fun `extreme wide photo can zoom out until the complete image is visible`() {
        val previewSize = IntSize(4000, 500)
        val viewport = IntSize(1000, 1000)
        val minimumZoom = minimumCropZoom(previewSize, viewport)

        assertEquals(0.125f, minimumZoom, 0.0001f)
        assertEquals(
            Rect(0f, 437.5f, 1000f, 562.5f),
            visibleImageBounds(previewSize, viewport, minimumZoom, Offset.Zero),
        )
    }

    @Test
    fun `full extreme image bounds map back to the complete source`() {
        val crop = cropBoundsInSource(
            previewSize = IntSize(4000, 500),
            sourceSize = IntSize(8000, 1000),
            viewport = IntSize(1000, 1000),
            zoom = 0.125f,
            imageOffset = Offset.Zero,
            cropRect = Rect(0f, 437.5f, 1000f, 562.5f),
        )

        assertEquals(PixelCrop(0, 0, 8000, 1000), crop)
    }
}
