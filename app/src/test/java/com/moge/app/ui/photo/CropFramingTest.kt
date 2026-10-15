package com.moge.app.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class CropFramingTest {
    private val viewport = IntSize(1000, 1000)

    @Test fun `initial frame includes every source pixel and fills one viewport dimension`() {
        for (image in listOf(IntSize(3000, 4000), IntSize(4000, 3000), IntSize(1000, 1000),
            IntSize(20000, 100), IntSize(100, 20000))) {
            val state = initialCropFraming(image, viewport)
            assertTrue(isWholePhotoSelected(image, viewport, state))
            assertTrue(state.cropRect.width <= viewport.width + .01f && state.cropRect.height <= viewport.height + .01f)
            assertTrue(state.cropRect.width == viewport.width.toFloat() || state.cropRect.height == viewport.height.toFloat())
            assertEquals(PixelCrop(0, 0, image.width, image.height), pixels(image, state))
        }
    }

    @Test fun `reframe and every animation frame preserve selected pixels including offcenter edge crops`() {
        for (image in listOf(IntSize(4000, 2000), IntSize(2000, 4000), IntSize(4000, 4000))) {
            val initial = initialCropFraming(image, viewport)
            val original = initial.cropRect
            val selected = initial.copy(cropRect = Rect(original.left, original.top,
                original.left + original.width * .4f, original.top + original.height * .6f))
            val target = fitCropFraming(selected, viewport)
            assertTrue(target.zoom > selected.zoom)
            assertTrue(target.cropRect.width == 1000f || target.cropRect.height == 1000f)
            for (progress in listOf(0f, .1f, .5f, .9f, 1f)) {
                assertEquals(pixels(image, selected), pixels(image, interpolateCropFraming(selected, target, progress)))
            }
            assertEquals("Starting the next pan must not jump to a different region", target.imageOffset,
                constrainImageOffset(target.imageOffset, image, viewport, target.zoom, target.cropRect))
            assertFalse(isWholePhotoSelected(image, viewport, target))
        }
    }

    @Test fun `reframing an already filled frame leaves manual zoom and pan unchanged`() {
        val current = CropFraming(3f, Offset(100f, -80f), Rect(0f, 100f, 1000f, 900f))
        assertEquals(current, fitCropFraming(current, viewport))
    }

    @Test fun `invalid or unmeasured frame cannot start an animation`() {
        val empty = CropFraming(1f, Offset.Zero, Rect.Zero)
        assertEquals(empty, fitCropFraming(empty, viewport))
        val selected = empty.copy(cropRect = Rect(0f, 0f, 100f, 200f))
        assertEquals(selected, fitCropFraming(selected, IntSize.Zero))
    }

    private fun pixels(image: IntSize, framing: CropFraming) = cropBoundsInSource(
        image, image, viewport, framing.zoom, framing.imageOffset, framing.cropRect)
}
