package com.moge.app.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.data.image.applyPhotoCrop
import com.moge.app.data.image.decodeEditedPhoto
import com.moge.app.data.image.writePhotoBitmap
import com.moge.app.data.image.writePhotoFileAtomically
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Compare the complete decode/crop/encode/save path on optional local photographs. */
@RunWith(AndroidJUnit4::class)
class PhotoCropPerformanceDeviceTest {
    @Test fun cropConfirmationKeepsPixelsAndRecordsLosslessSaveTimings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "photo-preprocess")
        val inputs = listOf("input.jpg", "detail/text.jpg")
        assumeTrue(inputs.all { File(directory, it).isFile })
        val rows = JSONArray()
        for ((index, name) in inputs.withIndex()) {
            val input = File(directory, name)
            val crop = PhotoCropEdit(1, .1f, .1f, .8f, .8f)
            val expected = decodeEditedPhoto(input.path, crop)
            val files = listOf(File(directory, "crop-baseline.png"), File(directory, "crop-fast.png"))
            try {
                // Warm both encoders, then alternate execution order to reduce first-run bias.
                files.forEach { writePhotoBitmap(it.path, expected) }
                repeat(3) { run ->
                    for (fast in if (run % 2 == 0) listOf(false, true) else listOf(true, false)) {
                        val output = files[if (fast) 1 else 0]
                        input.copyTo(output, overwrite = true)
                        val start = SystemClock.elapsedRealtimeNanos()
                        if (fast) applyPhotoCrop(output.path, crop) else {
                            val bitmap = decodeEditedPhoto(output.path, crop)
                            try {
                                writePhotoFileAtomically(output.path) { temporary ->
                                    temporary.outputStream().use {
                                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                                    }
                                }
                            } finally { bitmap.recycle() }
                        }
                        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                        val decoded = requireNotNull(BitmapFactory.decodeFile(output.path))
                        try { assertTrue("Crop pixels changed", expected.sameAs(decoded)) }
                        finally { decoded.recycle() }
                        rows.put(JSONObject().put("input", name).put("run", run).put("fast", fast)
                            .put("ms", elapsed).put("bytes", output.length())
                            .put("width", expected.width).put("height", expected.height))
                    }
                }
                // Include a transparent attachment: its alpha must still round-trip unchanged.
                if (index == 0) {
                    val alpha = Bitmap.createBitmap(67, 29, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(alpha.width * alpha.height) { i ->
                        android.graphics.Color.argb(i % 256, i * 23 % 256, i * 17 % 256, i * 31 % 256)
                    }
                    try {
                        alpha.setPixels(pixels, 0, alpha.width, 0, 0, alpha.width, alpha.height)
                        writePhotoBitmap(files[1].path, alpha)
                        val roundTrip = requireNotNull(BitmapFactory.decodeFile(files[1].path))
                        try { assertTrue(alpha.sameAs(roundTrip)) } finally { roundTrip.recycle() }
                    } finally { alpha.recycle() }
                }
            } finally { expected.recycle(); files.forEach { it.delete() } }
        }
        File(directory, "crop-priority-timings.json").writeText(rows.toString(2))
    }
}
