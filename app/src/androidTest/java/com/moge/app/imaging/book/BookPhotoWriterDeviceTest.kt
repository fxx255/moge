package com.moge.app.imaging.book

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File

@RunWith(AndroidJUnit4::class)
class BookPhotoWriterDeviceTest {
    @Test fun opaqueAndTransparentPhotosKeepPixelsAndPngFormat() {
        assertTrue(OpenCVLoader.initLocal())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (transparent in listOf(false, true)) {
            val file = File(context.cacheDir, "lossless-writer-$transparent.jpg")
            val source = Bitmap.createBitmap(67, 29, Bitmap.Config.ARGB_8888)
            source.setHasAlpha(transparent)
            val pixels = IntArray(source.width * source.height) { index ->
                Color.argb(if (transparent) index % 256 else 255,
                    index * 23 % 256, index * 17 % 256, index * 31 % 256)
            }
            source.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
            try {
                writeBookPhoto(file.path, source)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                assertEquals("image/png", options.outMimeType)
                val decoded = requireNotNull(BitmapFactory.decodeFile(file.path))
                try {
                    assertEquals(source.width, decoded.width)
                    assertEquals(source.height, decoded.height)
                    val expected = IntArray(pixels.size)
                    source.getPixels(expected, 0, source.width, 0, 0, source.width, source.height)
                    decoded.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
                    assertArrayEquals(expected, pixels)
                } finally { decoded.recycle() }
            } finally { source.recycle(); file.delete() }
        }
    }
}
