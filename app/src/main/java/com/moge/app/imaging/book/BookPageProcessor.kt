package com.moge.app.imaging.book

import android.graphics.Bitmap
import com.moge.app.data.image.decodePhotoBitmap
import com.moge.app.data.image.photoPixelBudget
import com.moge.app.imaging.QuestionPhotoEvidence
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import javax.inject.Inject

internal object BookImageLimits {
    const val MAX_EDGE = 4096
    val maxPixels: Long get() = photoPixelBudget()
}

/** PaperScan's BOOK_FLATTEN pipeline: geometry once, followed by color-preserving cleanup. */
internal class BookPageProcessor @Inject constructor() {
    fun render(path: String, evidence: QuestionPhotoEvidence): Bitmap {
        check(OpenCVLoader.initLocal()) { "图像处理组件未能加载" }
        val original = decodePhotoBitmap(path) ?: error("无法读取照片")
        try {
            val gray = Mat()
            val source = Mat()
            val corners = try {
                Utils.bitmapToMat(original, source)
                Imgproc.cvtColor(source, gray, Imgproc.COLOR_RGBA2GRAY)
                safePageCorners(DocumentDetector.detect(gray), evidence)
            } finally { source.release(); gray.release() }
            val page = PerspectiveRenderer.renderPage(original, corners, rectifyBook = true)
            return try {
                ColorDocumentEnhancer.apply(page.bitmap, page.boundary, book = true)
            } finally { page.bitmap.recycle() }
        } finally { original.recycle() }
    }
}

/** A frame inside a problem must not cut off the question, options or diagram labels. */
internal fun safePageCorners(detected: List<Corner>, evidence: QuestionPhotoEvidence): List<Corner> {
    if (!Geometry.isValid(detected) || Geometry.area(detected) < .45f) return Geometry.fullFrame
    fun inside(x: Float, y: Float): Boolean = detected.indices.all { i ->
        val a = detected[i]; val b = detected[(i + 1) % detected.size]
        (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x) >= -.015f
    }
    return if (evidence.regions.all {
        inside(it.left, it.top) && inside(it.right, it.top) &&
            inside(it.left, it.bottom) && inside(it.right, it.bottom)
    }) detected else Geometry.fullFrame
}
