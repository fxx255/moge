package com.moge.app.imaging.book

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.*

@RunWith(AndroidJUnit4::class)
class BookFlattenDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val output get() = File(context.filesDir, "dewarp-output").apply { mkdirs() }
    private fun save(bitmap: Bitmap, name: String) { File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it) } }

    @Test fun curvedFramesAndIndependentBlackRulesFlattenWithoutFoldingOrCropping() {
        assertTrue(OpenCVLoader.initLocal())
        val w = 640; val h = 1000
        val plane = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(plane).apply {
            drawColor(Color.rgb(243, 241, 235))
            val fill = Paint().apply { color = Color.rgb(248, 210, 209) }
            val border = Paint().apply { color = Color.rgb(180, 90, 85); style = Paint.Style.STROKE; strokeWidth = 1.7f }
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 17f }
            for (y in listOf(100, 320, 550, 780)) {
                drawRect(125f, y.toFloat(), 620f, y + 65f, fill); drawRect(125f, y.toFloat(), 620f, y + 65f, border)
                drawText("例题 矩阵 A + B = 123", 150f, y + 37f, text)
                drawText("一行文字及公式 a + b = c", 150f, y + 107f, text)
            }
            // These neutral rules never enter the chroma-based frame estimator.
            val rule = Paint().apply { color = Color.BLACK; strokeWidth = 1.5f }
            for (y in listOf(240, 465, 695, 925)) drawLine(160f, y.toFloat(), 610f, y.toFloat(), rule)
        }
        val source = Mat(); val warped = Mat(); val mapX = Mat(h, w, CvType.CV_32F); val mapY = Mat(h, w, CvType.CV_32F)
        val distorted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            Utils.bitmapToMat(plane, source)
            val xs = FloatArray(w * h); val ys = FloatArray(xs.size)
            for (y in 0 until h) for (x in 0 until w) {
                val curve = (30 * ((x - 140.0) / 480).coerceIn(0.0, 1.0).pow(2) - 8) * (.8 + .2 * cos(y * PI / h))
                xs[y * w + x] = x.toFloat(); ys[y * w + x] = (y - curve).toFloat()
            }
            mapX.put(0, 0, xs); mapY.put(0, 0, ys)
            Imgproc.remap(source, warped, mapX, mapY, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
            Utils.matToBitmap(warped, distorted)
            save(distorted, "synthetic-before.jpg")
            val model = assertNotNullModel(BookDewarper.analyze(warped))
            assertTrue(model.frameLines >= 6)
            val (mx, my) = model.warp.sourceMesh(65, 65)
            assertEquals(0f, mx.first(), .0001f); assertEquals(0f, my.first(), .0001f)
            assertEquals(1f, mx.last(), .0001f); assertEquals(1f, my.last(), .0001f)
            for (j in 0 until 64) for (i in 0 until 64) {
                val k = j * 65 + i
                val determinant = (mx[k + 1] - mx[k]) * (my[k + 65] - my[k]) - (mx[k + 65] - mx[k]) * (my[k + 1] - my[k])
                assertTrue("Page mesh must not fold", determinant > 0)
            }
            val page = PerspectiveRenderer.renderPage(distorted, Geometry.fullFrame, rectifyBook = true)
            try {
                assertEquals(w, page.bitmap.width); assertEquals(h, page.bitmap.height)
                fun drift(b: Bitmap, level: Int): Double {
                    val positions = (200..580 step 10).map { x ->
                        (level - 20..level + 35).minBy { y -> Color.red(b.getPixel(x, y)) }
                    }
                    return (positions.max() - positions.min()).toDouble()
                }
                val before = listOf(240, 465, 695, 925).map { drift(distorted, it) }
                val after = listOf(240, 465, 695, 925).map { drift(page.bitmap, it) }
                File(output, "synthetic-metrics.json").writeText(JSONObject().put("frame_lines", model.frameLines)
                    .put("text_lines", model.textLines).put("before_rule_drift", before.toString()).put("after_rule_drift", after.toString()).toString(2))
                save(distorted, "synthetic-before.jpg"); save(page.bitmap, "synthetic-after.jpg")
                assertTrue("Independent lines should flatten: $before -> $after", after.average() < before.average() * .35)
                assertTrue("Independent rules within supported frames flatten: $after", after.take(3).max() <= 4)
                // Outside the final measured box, correction fades toward the retained page
                // boundary. It should help without promising an unobserved flat surface.
                assertTrue("Unobserved bottom margin improves conservatively", after.last() < before.last() * .75)
            } finally { page.bitmap.recycle() }
        } finally { plane.recycle(); distorted.recycle(); listOf(source, warped, mapX, mapY).forEach { it.release() } }
    }

    @Test fun ordinaryTextAndDarkBoardDeclineUnsupportedMesh() {
        assertTrue(OpenCVLoader.initLocal())
        for (dark in listOf(false, true)) {
            val image = Bitmap.createBitmap(640, 800, Bitmap.Config.ARGB_8888)
            Canvas(image).apply {
                drawColor(if (dark) Color.DKGRAY else Color.WHITE)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dark) Color.YELLOW else Color.BLACK; textSize = 22f }
                for (y in 90..740 step 55) drawText("普通文本 a+b=c, 12345", 60f, y.toFloat(), paint)
            }
            val mat = Mat()
            try {
                Utils.bitmapToMat(image, mat); assertNull(BookDewarper.analyze(mat))
                val ordinary = PerspectiveRenderer.renderPage(image, Geometry.fullFrame)
                val requested = PerspectiveRenderer.renderPage(image, Geometry.fullFrame, rectifyBook = true)
                try { assertTrue("Unsupported pages use the existing geometry", ordinary.bitmap.sameAs(requested.bitmap)) }
                finally { ordinary.bitmap.recycle(); requested.bitmap.recycle() }
            } finally { mat.release(); image.recycle() }
        }
    }

    private fun assertNotNullModel(model: BookDewarper.Analysis?): BookDewarper.Analysis { assertNotNull(model); return requireNotNull(model) }
}
