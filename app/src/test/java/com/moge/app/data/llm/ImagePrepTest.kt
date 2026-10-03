package com.moge.app.data.llm

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImagePrepTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `sending consecutive images yields upright size limited base64 JPEGs`() {
        val file = photo()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, "6")
            saveAttributes()
        }
        repeat(3) {
            val encoded = ImagePrep.encodeForVision(file.path, maxDim = 60)!!
            assertFalse(encoded.contains('\n'))
            assertFalse(encoded.contains('\r'))
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            assertEquals(0xff, bytes[0].toInt() and 0xff)
            assertEquals(0xd8, bytes[1].toInt() and 0xff)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
            try {
                assertEquals(40, decoded.width)
                assertEquals(60, decoded.height)
            } finally { decoded.recycle() }
        }
        // An oversized caller limit must not overflow the sampling calculation.
        assertNotNull(ImagePrep.encodeForVision(file.path, maxDim = Int.MAX_VALUE))
    }

    @Test fun `invalid image or encoder settings return failure instead of a fake attachment`() {
        assertNull(ImagePrep.encodeForVision(File(temporary.root, "missing.jpg").path))
        assertNull(ImagePrep.encodeForVision(temporary.newFile().path))
        val corrupt = temporary.newFile().apply { writeText("not a photo") }
        assertNull(ImagePrep.encodeForVision(corrupt.path))
        val valid = photo()
        assertNull(ImagePrep.encodeForVision(valid.path, maxDim = 0))
        assertNull(ImagePrep.encodeForVision(valid.path, maxDim = -1))
        assertNull(ImagePrep.encodeForVision(valid.path, quality = 101))
    }

    private fun photo(): File = temporary.newFile().also { file ->
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(android.graphics.Color.WHITE)
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
