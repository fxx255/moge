package com.moge.app.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Test

class CropGeometryTest {

    @Test fun `nonfinite gesture offsets cannot poison crop rendering`() {
        val size = IntSize(1000, 1000)
        assertEquals(Offset.Zero, constrainImageOffset(Offset(Float.NaN, 0f), size, size, 2f))
        assertEquals(Offset.Zero, constrainImageOffset(Offset(0f, Float.POSITIVE_INFINITY), size, size, 2f))
        assertEquals(Offset.Zero, constrainImageOffset(Offset(10f, 20f), size, size, Float.NaN))
    }

    @Test fun `invalid crop coordinates are rejected before rounding into pixels`() {
        val size = IntSize(1000, 1000)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            cropBoundsInSource(size, size, size, 1f, Offset(Float.NaN, 0f), Rect(0f, 0f, 500f, 500f))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            cropBoundsInSource(size, size, size, Float.POSITIVE_INFINITY, Offset.Zero, Rect(0f, 0f, 500f, 500f))
        }
    }

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
    @Test
    fun `source ratio initializes a free crop without constraining later edge drags`() {
        val viewport = IntSize(1000, 1000)
        listOf(0.5f, 1f, 2f).forEach { initialRatio ->
            val initial = centeredCropRect(viewport, initialRatio)
            assertEquals(initialRatio, initial.width / initial.height, 0.0001f)
            val resized = resizeCropRectWithinBounds(
                initial, CropDragMode.RIGHT_EDGE, Offset(-100f, 75f),
                Rect(0f, 0f, 1000f, 1000f), minimumSize = 24f,
            )
            assertEquals(initial.left, resized.left, 0.0001f)
            assertEquals(initial.top, resized.top, 0.0001f)
            assertEquals(initial.bottom, resized.bottom, 0.0001f)
            assertEquals(initial.width - 100f, resized.width, 0.0001f)
        }
    }

    @Test
    fun `each edge changes only its own dimension with offset image bounds`() {
        val crop = Rect(150f, 250f, 450f, 650f)
        val bounds = Rect(50f, 100f, 650f, 800f)
        val delta = Offset(40f, -60f)
        listOf(
            CropDragMode.TOP_EDGE to Rect(150f, 190f, 450f, 650f),
            CropDragMode.RIGHT_EDGE to Rect(150f, 250f, 490f, 650f),
            CropDragMode.BOTTOM_EDGE to Rect(150f, 250f, 450f, 590f),
            CropDragMode.LEFT_EDGE to Rect(190f, 250f, 450f, 650f),
            CropDragMode.TOP_LEFT to Rect(190f, 190f, 450f, 650f),
            CropDragMode.TOP_RIGHT to Rect(150f, 190f, 490f, 650f),
            CropDragMode.BOTTOM_LEFT to Rect(190f, 250f, 450f, 590f),
            CropDragMode.BOTTOM_RIGHT to Rect(150f, 250f, 490f, 590f),
        ).forEach { (mode, expected) ->
            assertEquals(mode.name, expected, resizeCropRectWithinBounds(crop, mode, delta, bounds, 24f))
        }
    }

    @Test
    fun `free corners stop at image bounds and minimum width and height independently`() {
        val crop = Rect(150f, 250f, 450f, 650f)
        val bounds = Rect(50f, 100f, 650f, 800f)
        assertEquals(
            Rect(150f, 250f, 650f, 274f),
            resizeCropRectWithinBounds(crop, CropDragMode.BOTTOM_RIGHT, Offset(1000f, -1000f), bounds, 24f),
        )
        assertEquals(
            Rect(426f, 100f, 450f, 650f),
            resizeCropRectWithinBounds(crop, CropDragMode.TOP_LEFT, Offset(1000f, -1000f), bounds, 24f),
        )
    }

    @Test
    fun `zooming out clamps each free crop dimension to visible photo pixels`() {
        assertEquals(
            Rect(30f, 350f, 970f, 650f),
            constrainCropRectToBounds(Rect(30f, 100f, 970f, 900f), Rect(0f, 350f, 1000f, 650f)),
        )
    }

    @Test
    fun `non proportional corner resize maps independently to saved pixel dimensions`() {
        val resized = resizeCropRectWithinBounds(
            Rect(100f, 150f, 300f, 450f), CropDragMode.BOTTOM_RIGHT, Offset(90f, -50f),
            Rect(0f, 0f, 1000f, 1000f), 24f,
        )
        assertEquals(
            PixelCrop(400, 600, 1160, 1000),
            cropBoundsInSource(IntSize(1000, 1000), IntSize(4000, 4000), IntSize(1000, 1000), 1f, Offset.Zero, resized),
        )
    }
}
