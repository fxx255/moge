package com.moge.app.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.moge.app.data.image.PhotoCropEdit
import org.junit.Assert.*
import org.junit.Test

class CropRotationTest {
    @Test fun `four rotations retain asymmetric offcenter selections for portrait landscape and thin photos`() {
        for (image in listOf(IntSize(3000, 4000), IntSize(4000, 3000), IntSize(1000, 1000),
            IntSize(20000, 100), IntSize(100, 20000))) {
            for (viewport in listOf(IntSize(1000, 1000), IntSize(900, 500), IntSize(500, 900))) {
                val initial = initialCropFraming(image, viewport)
                val full = initial.cropRect
                var framing = initial.copy(cropRect = Rect(
                    full.left + full.width * .12f, full.top + full.height * .18f,
                    full.left + full.width * .53f, full.top + full.height * .79f))
                // Include zoomed and panned photos, rather than only centered whole-photo rotations.
                framing = transformLinkedCropFraming(framing, viewport, framing.zoom * 1.2f,
                    Offset(17f, -23f), framing.cropRect.center)
                val original = selection(image, viewport, framing)
                var expected = original
                var currentImage = image
                repeat(4) {
                    val before = framing
                    framing = rotateCropFramingCounterclockwise(framing, currentImage, viewport)
                    currentImage = IntSize(currentImage.height, currentImage.width)
                    expected = PhotoCropEdit(left = expected.top, top = 1f - expected.left - expected.width,
                        width = expected.height, height = expected.width)
                    assertSelection(expected, selection(currentImage, viewport, framing))
                    assertTrue(framing.cropRect.left >= -.01f && framing.cropRect.top >= -.01f)
                    assertTrue(framing.cropRect.right <= viewport.width + .01f &&
                        framing.cropRect.bottom <= viewport.height + .01f)
                    if (viewport.width == viewport.height) {
                        assertEquals(before.cropRect.height, framing.cropRect.width, .01f)
                        assertEquals(before.cropRect.width, framing.cropRect.height, .01f)
                    }
                }
                assertSelection(original, selection(image, viewport, framing))
            }
        }
    }

    @Test fun `a whole photo stays whole after rotation including a rectangular viewport`() {
        for (viewport in listOf(IntSize(1000, 1000), IntSize(900, 500))) {
            var image = IntSize(4000, 1000)
            var framing = initialCropFraming(image, viewport)
            repeat(4) {
                framing = rotateCropFramingCounterclockwise(framing, image, viewport)
                image = IntSize(image.height, image.width)
                assertTrue(isWholePhotoSelected(image, viewport, framing))
                assertEquals(PixelCrop(0, 0, image.width, image.height), cropBoundsInSource(
                    image, image, viewport, framing.zoom, framing.imageOffset, framing.cropRect))
            }
        }
    }

    private fun selection(image: IntSize, viewport: IntSize, framing: CropFraming) =
        cropSelectionEdit(image, viewport, framing.zoom, framing.imageOffset, framing.cropRect)

    private fun assertSelection(expected: PhotoCropEdit, actual: PhotoCropEdit) {
        assertEquals(expected.left, actual.left, .0001f)
        assertEquals(expected.top, actual.top, .0001f)
        assertEquals(expected.width, actual.width, .0001f)
        assertEquals(expected.height, actual.height, .0001f)
    }
}
