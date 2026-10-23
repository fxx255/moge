package com.moge.app.imaging.book

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.image.decodePhotoBitmap
import com.moge.app.imaging.PhotoPixelWork
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt
import org.opencv.android.OpenCVLoader

/** Optional private photograph; device output and timings stay outside source control. */
@RunWith(AndroidJUnit4::class)
class BookPhotoQualityDeviceTest {
    @Before fun loadNativeProcessor() { assertTrue(OpenCVLoader.initLocal()) }

    @Test fun cleanupRemovesFaintShowThroughWithoutThickeningStrokesOrBlurringEdges() {
        val source = Bitmap.createBitmap(720, 900, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.rgb(240, 238, 232))
        try {
            val canvas = Canvas(source)
            val paint = Paint().apply { color = Color.rgb(185, 183, 179) }
            canvas.drawText("Back side printing", 50f, 250f, paint.apply { textSize = 32f })
            canvas.drawRect(55f, 280f, 57f, 310f, paint)
            paint.color = Color.rgb(25, 24, 23)
            canvas.drawRect(100f, 90f, 103f, 200f, paint)
            paint.color = Color.rgb(150, 149, 145)
            canvas.drawRect(99f, 90f, 100f, 200f, paint)
            canvas.drawRect(103f, 90f, 104f, 200f, paint)
            paint.color = Color.rgb(25, 24, 23)
            canvas.drawRect(300f, 400f, 600f, 401f, paint)
            val result = ColorDocumentEnhancer.apply(source, book = true, pixelWork = PhotoPixelWork(3))
            try {
                val serial = ColorDocumentEnhancer.apply(source, book = true, pixelWork = PhotoPixelWork(1))
                try { assertTrue("CPU partitioning must preserve every stroke and stripe seam", serial.sameAs(result)) }
                finally { serial.recycle() }
                assertEquals(source.width, result.width)
                assertEquals(source.height, result.height)
                assertTrue("White paper must stay clean", Color.red(result.getPixel(400, 200)) >= 250)
                assertTrue("Faint reverse-side marks must fade", Color.red(result.getPixel(55, 290)) >= 240)
                for (y in listOf(94, 95, 96, 97, 191, 192, 193)) {
                    assertTrue("Stroke cores survive across stripe boundaries", Color.red(result.getPixel(101, y)) < 40)
                    assertTrue("Gray glyph edges must not become black", Color.red(result.getPixel(99, y)) > 140)
                    assertTrue("Cleanup must not bleed into neighboring paper", Color.red(result.getPixel(98, y)) >= 250)
                }
                assertTrue("Single-pixel rules must not be smoothed away", Color.red(result.getPixel(450, 400)) < 40)
            } finally { result.recycle() }
        } finally { source.recycle() }
    }

    @Test fun fullSizePageIsNotReducedToThePreviewPixelBudget() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, "full-size-page.png")
        val source = Bitmap.createBitmap(2304, 2048, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.rgb(240, 238, 232))
            source.setPixel(113, 95, Color.BLACK)
            input.outputStream().use { assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { source.recycle() }
        try {
            val output = BookPageProcessor().render(input.path)
            try {
                assertEquals(2304, output.width)
                assertEquals(2048, output.height)
                assertTrue("Native one-pixel detail survives", Color.red(output.getPixel(113, 95)) < 40)
            } finally { output.recycle() }
        } finally { input.delete() }
    }

    @Test fun suppliedTextAndHandwritingRetainNativeDetail() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "photo-preprocess/detail")
        assumeTrue(File(directory, "text.jpg").isFile && File(directory, "notes.jpg").isFile)
        val label = InstrumentationRegistry.getArguments().getString("photoRun", "current")
        for (name in listOf("text", "notes")) {
            val output = BookPageProcessor().render(File(directory, "$name.jpg").path)
            try {
                File(directory, "$name-$label.png").outputStream().use {
                    assertTrue(output.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { output.recycle() }
        }
    }

    @Test fun warmShadowIsNeutralizedWhileColoredPanelsAndThinRulesSurvive() {
        val width = 720
        val height = 900
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(source).apply {
                drawColor(Color.rgb(240, 238, 232))
                val paint = Paint().apply { color = Color.rgb(15, 90, 155) }
                drawRect(390f, 280f, 620f, 310f, paint)
                paint.color = Color.rgb(245, 205, 205)
                drawRect(390f, 510f, 620f, 580f, paint)
                paint.color = Color.rgb(245, 235, 205)
                drawRect(390f, 620f, 620f, 690f, paint)
                paint.color = Color.BLACK
                drawRect(40f, 750f, 680f, 752f, paint)
            }
            val pixels = IntArray(width * height)
            source.getPixels(pixels, 0, width, 0, 0, width, height)
            for (y in 0 until height) for (x in 0 until width) {
                val shade = ((x / width.toFloat() * .6f + y / height.toFloat() * .4f - .3f) / .4f).coerceIn(0f, 1f)
                val p = y * width + x
                val original = pixels[p]
                pixels[p] = Color.rgb((Color.red(original) * (1 - .43f * shade)).roundToInt(),
                    (Color.green(original) * (1 - .52f * shade)).roundToInt(),
                    (Color.blue(original) * (1 - .62f * shade)).roundToInt())
            }
            source.setPixels(pixels, 0, width, 0, 0, width, height)
            val result = ColorDocumentEnhancer.apply(source, book = true)
            try {
                val paper = result.getPixel(620, 400)
                assertTrue("Shadowed paper should be bright", minOf(Color.red(paper), Color.green(paper), Color.blue(paper)) > 240)
                val blue = result.getPixel(500, 295)
                assertTrue("Blue print retains its hue", Color.blue(blue) - Color.red(blue) > 30)
                val pink = result.getPixel(500, 545)
                assertTrue("Pink panel retains its hue", Color.red(pink) - Color.green(pink) > 20)
                val yellow = result.getPixel(500, 655)
                assertTrue("Pale yellow panel must not be mistaken for a shadow", Color.red(yellow) - Color.blue(yellow) > 20)
                assertTrue("Thin diagram rules remain dark", Color.red(result.getPixel(500, 750)) < 40)
            } finally { result.recycle() }
        } finally { source.recycle() }
    }

    @Test fun suppliedShadowPhotoRecordsFullResolutionProcessing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.filesDir, "photo-preprocess/shadow-input.jpg")
        assumeTrue(input.isFile)
        val label = InstrumentationRegistry.getArguments().getString("photoRun", "current")
        val output = File(context.filesDir, "photo-preprocess/shadow-$label.jpg")
        val clock = SystemClock.elapsedRealtime()
        val result = BookPageProcessor().render(input.path)
        val rendered = SystemClock.elapsedRealtime()
        try {
            output.outputStream().use { assertTrue(result.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
            val source = requireNotNull(decodePhotoBitmap(input.path))
            try {
                assertTrue("Retain the complete photographed page", result.width >= source.width * .85)
                assertTrue("Retain the complete photographed page", result.height >= source.height * .85)
            } finally { source.recycle() }
            val metrics = JSONObject().put("render_ms", rendered - clock)
                .put("save_ms", SystemClock.elapsedRealtime() - rendered)
                .put("width", result.width).put("height", result.height)
            File(output.parentFile, "shadow-$label.json").writeText(metrics.toString(2))
        } finally { result.recycle() }
    }
}
