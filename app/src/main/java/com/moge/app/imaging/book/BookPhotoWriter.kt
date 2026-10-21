package com.moge.app.imaging.book

import android.graphics.Bitmap
import android.util.Log
import com.moge.app.data.image.writePhotoBitmap
import com.moge.app.data.image.writePhotoFileAtomically
import org.opencv.android.Utils
import org.opencv.core.CvException
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

/** Fast, lossless encoding after BookPageProcessor has initialized OpenCV. */
internal fun writeBookPhoto(path: String, bitmap: Bitmap) {
    // Keep Android's premultiplied-alpha conversion for transparent attachments.
    if (bitmap.hasAlpha()) return writePhotoBitmap(path, bitmap)
    try {
        writePhotoFileAtomically(path) { temporary ->
            val pixels = Mat()
            val options = MatOfInt(Imgcodecs.IMWRITE_PNG_COMPRESSION, 1,
                Imgcodecs.IMWRITE_PNG_STRATEGY, Imgcodecs.IMWRITE_PNG_STRATEGY_RLE)
            try {
                Utils.bitmapToMat(bitmap, pixels)
                Imgproc.cvtColor(pixels, pixels, Imgproc.COLOR_RGBA2BGR)
                check(Imgcodecs.imwrite(temporary.path, pixels, options)) { "无法保存照片" }
            } finally { pixels.release(); options.release() }
        }
    } catch (error: CvException) {
        // Release native buffers before retrying with the low-memory platform encoder.
        Log.w("MogePhoto", "Native PNG encoding unavailable; using platform PNG", error)
        writePhotoBitmap(path, bitmap)
    }
}
