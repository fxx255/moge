package com.moge.app.data.image

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.unit.IntSize
import com.moge.app.ui.photo.THUMB_MAX_PX
import com.moge.app.ui.photo.ThumbnailCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PixelRegionDecoder::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoThumbnailDecoderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `tall photo retains screen resolution and fine lines in its visible region`() {
        val file = stripedPhoto(640, 5120)
        val old = requireNotNull(decodePhotoBitmap(file.path, 480))
        val thumbnail = requireNotNull(decodePhotoThumbnail(file.path, 480, 360))
        try {
            assertTrue("Whole-image edge limits undersample the width of a tall photo", old.width < 100)
            assertEquals(480, thumbnail.width)
            assertEquals(360, thumbnail.height)
            var edges = 0
            for (x in 1 until thumbnail.width) {
                if ((Color.red(thumbnail.getPixel(x - 1, 180)) < 128) !=
                    (Color.red(thumbnail.getPixel(x, 180)) < 128)) edges++
            }
            assertTrue("Text-sized strokes must survive thumbnail decoding", edges >= 70)
        } finally { old.recycle(); thumbnail.recycle() }
    }

    @Test fun `wide photos use the display height and small sources are never enlarged`() {
        val wide = requireNotNull(decodePhotoThumbnail(stripedPhoto(5120, 640).path, 480, 360))
        val small = requireNotNull(decodePhotoThumbnail(stripedPhoto(64, 48).path, 1000, 750))
        try {
            assertEquals(480, wide.width)
            assertEquals(360, wide.height)
            assertEquals(64, small.width)
            assertEquals(48, small.height)
        } finally { wide.recycle(); small.recycle() }
    }

    @Test fun `thumbnail cache distinguishes display sizes and edit revisions`() = runBlocking {
        val file = stripedPhoto(640, 1280)
        try {
            val first = ThumbnailCache.load(file.path, THUMB_MAX_PX, revision = 7, cropSize = IntSize(480, 360))!!
            val small = ThumbnailCache.load(file.path, THUMB_MAX_PX, revision = 7, cropSize = IntSize(240, 180))!!
            assertEquals(480, first.width)
            assertEquals(240, small.width)
            assertSame(first, ThumbnailCache.peek(file.path, THUMB_MAX_PX, revision = 7, cropSize = IntSize(480, 360)))
            assertNull(ThumbnailCache.peek(file.path, THUMB_MAX_PX, revision = 8, cropSize = IntSize(480, 360)))
        } finally { ThumbnailCache.clear() }
    }

    private fun stripedPhoto(width: Int, height: Int) = temporary.newFile().also { file ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.BLACK }
            for (x in 0 until width step 8) canvas.drawRect(x.toFloat(), 0f, x + 4f, height.toFloat(), paint)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
