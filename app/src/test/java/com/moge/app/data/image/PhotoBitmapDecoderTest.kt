package com.moge.app.data.image

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowLog
import org.robolectric.util.ReflectionHelpers
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PixelRegionDecoder::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoBitmapDecoderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `sampling bounds both edges and pixel count without integer overflow`() {
        listOf(8192 to 6144, 4096 to 4096, Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 1).forEach { (w, h) ->
            val sample = photoSampleSize(w, h, 4096, MAX_PHOTO_PIXELS)
            val width = (w.toLong() + sample - 1) / sample
            val height = (h.toLong() + sample - 1) / sample
            assertTrue(width <= 4096 && height <= 4096)
            assertTrue(width * height <= MAX_PHOTO_PIXELS)
            assertEquals(0, sample and (sample - 1))
        }
        assertEquals(4, photoSampleSize(8192, 6144, 4096, MAX_PHOTO_PIXELS))
    }

    @Test fun `48 megapixel file decodes to a bounded software sRGB bitmap`() {
        val file = largePhoto()
        val decoded = decodePhotoBitmap(file.path)!!
        try {
            assertTrue(decoded.width <= 4096 && decoded.height <= 4096)
            assertTrue(decoded.width.toLong() * decoded.height <= photoPixelBudget())
            assertEquals(Bitmap.Config.ARGB_8888, decoded.config)
            assertTrue(decoded.colorSpace!!.isSrgb)
            assertTrue(decoded.allocationByteCount <= photoPixelBudget() * 4)
        } finally { decoded.recycle() }
        val crop = decodePhotoCrop(file.path, 0) { w, h -> Rect(0, 0, w, h) }!!
        try {
            assertTrue(crop.width <= 4096 && crop.height <= 4096)
            assertTrue(crop.width.toLong() * crop.height <= photoPixelBudget())
        } finally { crop.recycle() }
    }

    @Test fun `small crop retains source resolution instead of cropping a reduced whole image`() {
        val crop = decodePhotoCrop(largePhoto().path, 0) { w, h ->
            Rect(w / 2, h / 2, w / 2 + w / 64, h / 2 + h / 64)
        }!!
        try {
            val decoderFailures = ShadowLog.getLogsForTag("MogePhoto").joinToString {
                "${it.msg}: ${it.throwable?.let { error -> error.stackTraceToString() }}"
            }
            assertEquals(decoderFailures, 128, crop.width)
            assertEquals(96, crop.height)
            assertEquals(Color.rgb(40, 120, 200), crop.getPixel(64, 48))
        } finally { crop.recycle() }
    }

    @Test fun `region selection follows every EXIF orientation and user quarter turn`() {
        val originalColors = listOf(
            listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW),
            listOf(Color.GREEN, Color.RED, Color.YELLOW, Color.BLUE),
            listOf(Color.YELLOW, Color.BLUE, Color.GREEN, Color.RED),
            listOf(Color.BLUE, Color.YELLOW, Color.RED, Color.GREEN),
            listOf(Color.RED, Color.BLUE, Color.GREEN, Color.YELLOW),
            listOf(Color.BLUE, Color.RED, Color.YELLOW, Color.GREEN),
            listOf(Color.YELLOW, Color.GREEN, Color.BLUE, Color.RED),
            listOf(Color.GREEN, Color.YELLOW, Color.RED, Color.BLUE),
        )
        for (orientation in 1..8) {
            val file = quadrantPhoto()
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            var colors = originalColors[orientation - 1]
            for (turn in 0..3) {
                val swapsAxes = (orientation >= 5) xor (turn % 2 == 1)
                val width = if (swapsAxes) 60 else 80
                val height = if (swapsAxes) 80 else 60
                val upright = decodePhotoBitmap(file.path, quarterTurns = turn)!!
                try {
                    assertEquals(width, upright.width)
                    assertEquals(height, upright.height)
                    assertColorNear(colors.first(), upright.getPixel(width / 4, height / 4))
                } finally { upright.recycle() }
                val crop = decodePhotoCrop(file.path, turn) { w, h ->
                    assertEquals(width, w)
                    assertEquals(height, h)
                    Rect(w / 8, h / 8, w / 8 + w / 4, h / 8 + h / 4)
                }!!
                try {
                    assertEquals(width / 4, crop.width)
                    assertEquals(height / 4, crop.height)
                    assertColorNear(colors.first(), crop.getPixel(crop.width / 2, crop.height / 2))
                } finally { crop.recycle() }
                colors = listOf(colors[2], colors[0], colors[3], colors[1])
            }
        }
    }

    @Test fun `missing damaged and invalid crop input fail without replacing the original`() {
        assertNull(decodePhotoBitmap(File(temporary.root, "missing.jpg").path))
        val corrupt = temporary.newFile().apply { writeText("not a photo") }
        assertNull(decodePhotoBitmap(corrupt.path))
        assertNull(decodePhotoCrop(corrupt.path, 0) { w, h -> Rect(0, 0, w, h) })
        val valid = quadrantPhoto()
        val original = valid.readBytes()
        assertNull(decodePhotoCrop(valid.path, 0) { _, _ -> Rect(-1, 0, 20, 20) })
        assertArrayEquals(original, valid.readBytes())
    }

    @Test fun `unsupported region codec falls back to an upright usable crop`() {
        val file = quadrantPhoto()
        PixelRegionDecoder.rejectRegions = true
        try {
            val crop = decodePhotoCrop(file.path, 1) { w, h ->
                Rect(w / 8, h / 8, w / 8 + w / 4, h / 8 + h / 4)
            }!!
            try {
                assertEquals(15, crop.width)
                assertEquals(20, crop.height)
                assertColorNear(Color.BLUE, crop.getPixel(7, 10))
            } finally { crop.recycle() }
        } finally { PixelRegionDecoder.rejectRegions = false }
    }

    private fun assertColorNear(expected: Int, actual: Int) {
        assertTrue("Expected $expected, got $actual", listOf(
            Color.red(expected) - Color.red(actual),
            Color.green(expected) - Color.green(actual),
            Color.blue(expected) - Color.blue(actual),
        ).all { kotlin.math.abs(it) <= 20 })
    }

    private fun quadrantPhoto(): File = temporary.newFile().also { file ->
        val bitmap = Bitmap.createBitmap(80, 60, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 60) for (x in 0 until 80) {
                bitmap.setPixel(x, y, when {
                    y < 30 && x < 40 -> Color.RED
                    y < 30 -> Color.GREEN
                    x < 40 -> Color.BLUE
                    else -> Color.YELLOW
                })
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    /** Write rows into a PNG stream; the test itself never allocates a 48MP bitmap. */
    private fun largePhoto(): File = temporary.newFile().also { file ->
        val width = 8192
        val height = 6144
        val header = ByteArrayOutputStream().apply {
            DataOutputStream(this).apply {
                writeInt(width); writeInt(height)
                write(byteArrayOf(8, 2, 0, 0, 0))
            }
        }.toByteArray()
        val row = ByteArray(width * 3 + 1)
        for (x in 0 until width) {
            row[x * 3 + 1] = 40
            row[x * 3 + 2] = 120
            row[x * 3 + 3] = 200.toByte()
        }
        val data = ByteArrayOutputStream().apply {
            DeflaterOutputStream(this).use { compressed -> repeat(height) { compressed.write(row) } }
        }.toByteArray()
        DataOutputStream(file.outputStream()).use { output ->
            output.write(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))
            fun chunk(name: String, bytes: ByteArray) {
                val type = name.toByteArray(Charsets.US_ASCII)
                output.writeInt(bytes.size)
                output.write(type); output.write(bytes)
                output.writeInt(CRC32().apply { update(type); update(bytes) }.value.toInt())
            }
            chunk("IHDR", header)
            chunk("IDAT", data)
            chunk("IEND", byteArrayOf())
        }
    }
}

/**
 * Robolectric 4.14's region shadow returns blank pixels and ignores inSampleSize, even in
 * native graphics mode. Use an independent JVM region reader for that API; orientation,
 * selection mapping, sampling decisions and bitmap transforms still run in the app code.
 */
@Implements(BitmapRegionDecoder::class)
class PixelRegionDecoder {
    private lateinit var path: String

    @Implementation
    fun decodeRegion(rectangle: Rect, options: BitmapFactory.Options): Bitmap {
        if (rejectRegions) throw java.io.IOException("Simulated unsupported region codec")
        // Android's test compile classpath excludes java.desktop; ImageIO is present on the test JVM.
        val imageIO = Class.forName("javax.imageio.ImageIO")
        val readerClass = Class.forName("javax.imageio.ImageReader")
        val parametersClass = Class.forName("javax.imageio.ImageReadParam")
        val rectangleClass = Class.forName("java.awt.Rectangle")
        val intType = java.lang.Integer.TYPE
        val input = imageIO.getMethod("createImageInputStream", Any::class.java).invoke(null, File(path))
        (input as java.io.Closeable).use {
            val readers = imageIO.getMethod("getImageReaders", Any::class.java).invoke(null, input) as Iterator<*>
            val reader = readers.next()!!
            try {
                readerClass.getMethod("setInput", Any::class.java).invoke(reader, input)
                val parameters = readerClass.getMethod("getDefaultReadParam").invoke(reader)
                val region = rectangleClass.getConstructor(intType, intType, intType, intType)
                    .newInstance(rectangle.left, rectangle.top, rectangle.width(), rectangle.height())
                parametersClass.getMethod("setSourceRegion", rectangleClass).invoke(parameters, region)
                val sample = options.inSampleSize.coerceAtLeast(1)
                parametersClass.getMethod("setSourceSubsampling", intType, intType, intType, intType)
                    .invoke(parameters, sample, sample, 0, 0)
                val image = readerClass.getMethod("read", intType, parametersClass).invoke(reader, 0, parameters)
                val imageClass = Class.forName("java.awt.image.BufferedImage")
                val width = imageClass.getMethod("getWidth").invoke(image) as Int
                val height = imageClass.getMethod("getHeight").invoke(image) as Int
                val pixels = imageClass.getMethod("getRGB", intType, intType, intType, intType,
                    IntArray::class.java, intType, intType).invoke(image, 0, 0, width, height, null, 0, width) as IntArray
                return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
            } finally { readerClass.getMethod("dispose").invoke(reader) }
        }
    }

    @Implementation
    fun recycle() = Unit // The JVM reader is closed within decodeRegion.

    companion object {
        var rejectRegions = false
        @JvmStatic
        @Implementation
        fun newInstance(path: String, @Suppress("UNUSED_PARAMETER") shareable: Boolean): BitmapRegionDecoder {
            val decoder = ReflectionHelpers.callConstructor(BitmapRegionDecoder::class.java,
                ReflectionHelpers.ClassParameter.from(java.lang.Long.TYPE, 0L))
            Shadow.extract<PixelRegionDecoder>(decoder).path = path
            return decoder
        }
    }
}
