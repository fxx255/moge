package com.moge.app.ui.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportImageQualityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun reset() { Dispatchers.resetMain() }

    @Test fun `banded PNG has exact dimensions and lossless pixels across seams and final partial band`() = runTest {
        val files = ExportFiles.create(context)
        val width = 2160
        val height = 1301
        val paint = Paint()
        val bands = mutableListOf<Int>()
        try {
            val page = files.writePage(width, height, 1) { canvas, top, bottom ->
                bands += bottom - top
                for (y in top until bottom) {
                    paint.color = Color.rgb(y % 256, (y * 3) % 256, (y * 7) % 256)
                    canvas.drawRect(0f, y.toFloat(), width.toFloat(), (y + 1).toFloat(), paint)
                }
            }
            val bitmap = BitmapFactory.decodeFile(page.path)!!
            try {
                assertEquals(width, bitmap.width)
                assertEquals(height, bitmap.height)
                assertTrue(bands.all { it <= 512 })
                for (y in listOf(0, 511, 512, 1023, 1024, 1300)) {
                    assertEquals(Color.rgb(y % 256, (y * 3) % 256, (y * 7) % 256), bitmap.getPixel(100, y))
                }
            } finally { bitmap.recycle() }
            val bytes = page.readBytes()
            val crc = java.util.zip.CRC32()
            var offset = 8
            while (offset < bytes.size) {
                val length = java.nio.ByteBuffer.wrap(bytes, offset, 4).int
                crc.reset(); crc.update(bytes, offset + 4, length + 4)
                assertEquals(crc.value.toInt(), java.nio.ByteBuffer.wrap(bytes, offset + 8 + length, 4).int)
                offset += length + 12
            }
            assertEquals(bytes.size, offset)
        } finally { files.close(force = true) }
    }

    @Test fun `portrait export keeps width detail rather than sampling to a short longest side`() {
        val path = File(context.cacheDir, "portrait-detail.png")
        val source = Bitmap.createBitmap(2400, 4800, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.WHITE)
            path.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { source.recycle() }
        val bitmap = decodeExportImage(path.path)!!
        try {
            assertEquals(ExportLimits.CONTENT_WIDTH, bitmap.width)
            assertEquals(ExportLimits.CONTENT_WIDTH * 2, bitmap.height)
        } finally { bitmap.recycle() }
    }

    @Test fun `long preview tile retains full width for zoom and a sharp screen sized width without shrinking for height`() = runTest {
        val files = ExportFiles.create(context)
        try {
            val page = files.writePage(2160, 24000, 1) { canvas, _, _ -> canvas.drawColor(Color.WHITE) }
            val tiles = exportPreviewTiles(24000)
            assertEquals(0, tiles.first().top)
            assertEquals(24000, tiles.last().bottom)
            assertTrue(tiles.zipWithNext().all { (a, b) -> a.bottom == b.top })
            val screen = decodeExportPreviewTile(page.path, 2160, tiles[12], 600)!!
            val zoom = decodeExportPreviewTile(page.path, 2160, tiles[12], 1500)!!
            try {
                assertTrue(screen.width >= 600)
                assertEquals(2160, zoom.width)
                assertEquals(1024, zoom.height)
                // Robolectric's region decoder supplies dimensions but does not decode pixels.
                // Pixel fidelity of regions is covered by ExportQualityDeviceTest on Android.
            } finally { screen.recycle(); zoom.recycle() }
        } finally { files.close(force = true) }
    }
}
