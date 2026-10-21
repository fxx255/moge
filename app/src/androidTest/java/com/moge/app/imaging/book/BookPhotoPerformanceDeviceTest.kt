package com.moge.app.imaging.book

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.image.decodePhotoBitmap
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.io.File
import kotlin.math.abs

/** Optional local photographs: measure the real stages, never commit private inputs or outputs. */
@RunWith(AndroidJUnit4::class)
class BookPhotoPerformanceDeviceTest {
    @Test fun suppliedPhotosRecordStageTimingsAndPixelDifferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "photo-preprocess")
        val inputs = listOf("shadow-input.jpg", "input.jpg", "detail/text.jpg", "detail/notes.jpg")
        assumeTrue(inputs.all { File(directory, it).isFile })
        assertTrue(OpenCVLoader.initLocal())
        val arguments = InstrumentationRegistry.getArguments()
        val label = arguments.getString("photoRun", "current")!!
        val reference = arguments.getString("photoReference")
        val repeats = arguments.getString("photoRepeats", "3")!!.toInt().coerceIn(1, 5)
        val warmups = arguments.getString("photoWarmups", "0")!!.toInt().coerceIn(0, 2)
        val tolerance = arguments.getString("photoMeanTolerance", "0")!!.toDouble()
        repeat(warmups) { BookPageProcessor().render(File(directory, inputs.first()).path).recycle() }
        val rows = JSONArray()
        for ((index, input) in inputs.withIndex()) repeat(repeats) { run ->
            val start = SystemClock.elapsedRealtime()
            val original = requireNotNull(decodePhotoBitmap(File(directory, input).path, preserveResolution = true))
            val decoded = SystemClock.elapsedRealtime()
            val result = try {
                ColorDocumentEnhancer.apply(original, book = true)
            } finally { original.recycle() }
            val enhanced = SystemClock.elapsedRealtime()
            val output = File(directory, "timing-$index-$label.png")
            try {
                writeBookPhoto(output.path, result)
                val saved = SystemClock.elapsedRealtime()
                val row = JSONObject().put("input", input).put("run", run)
                    .put("width", result.width).put("height", result.height)
                    .put("decode_ms", decoded - start).put("geometry_ms", 0)
                    .put("enhance_ms", enhanced - decoded).put("save_ms", saved - enhanced)
                    .put("total_ms", saved - start).put("bytes", output.length())
                if (reference != null && run == repeats - 1) {
                    val previous = requireNotNull(BitmapFactory.decodeFile(File(directory, "timing-$index-$reference.png").path))
                    try {
                        val difference = pixelDifferences(result, previous)
                        row.put("difference", difference)
                        assertTrue("Pixel difference exceeds $tolerance: $difference",
                            difference.getDouble("mean_channel_error") <= tolerance)
                    } finally { previous.recycle() }
                }
                rows.put(row)
            } finally { result.recycle() }
        }
        File(directory, "timing-$label.json").writeText(rows.toString(2))
    }

    private fun pixelDifferences(expected: Bitmap, actual: Bitmap): JSONObject {
        org.junit.Assert.assertEquals(expected.width, actual.width)
        org.junit.Assert.assertEquals(expected.height, actual.height)
        val left = IntArray(expected.width)
        val right = IntArray(expected.width)
        val histogram = LongArray(256)
        var total = 0L
        var changed = 0L
        for (y in 0 until expected.height) {
            expected.getPixels(left, 0, expected.width, 0, y, expected.width, 1)
            actual.getPixels(right, 0, actual.width, 0, y, actual.width, 1)
            for (x in left.indices) {
                if (left[x] != right[x]) changed++
                for (shift in 0..16 step 8) {
                    val delta = abs(((left[x] ushr shift) and 255) - ((right[x] ushr shift) and 255))
                    histogram[delta]++
                    total += delta
                }
            }
        }
        val channels = expected.width.toLong() * expected.height * 3
        var cumulative = 0L
        val percentile99 = histogram.indices.first { cumulative += histogram[it]; cumulative >= channels * .99 }
        return JSONObject().put("mean_channel_error", total.toDouble() / channels)
            .put("p99_channel_error", percentile99).put("max_channel_error", histogram.indexOfLast { it > 0 })
            .put("changed_pixels", changed).put("channels_over_12_fraction", histogram.drop(13).sum().toDouble() / channels)
    }
}
