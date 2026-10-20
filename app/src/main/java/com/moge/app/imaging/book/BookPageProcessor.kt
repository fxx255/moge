package com.moge.app.imaging.book

import android.graphics.Bitmap
import com.moge.app.data.image.decodePhotoBitmap
import com.moge.app.data.image.photoPixelBudget
import org.opencv.android.OpenCVLoader
import javax.inject.Inject

internal object BookImageLimits {
    const val MAX_EDGE = 4096
    val maxPixels: Long get() = photoPixelBudget()
}

/** PaperScan's BOOK_FLATTEN pipeline: geometry once, followed by color-preserving cleanup. */
internal class BookPageProcessor @Inject constructor() {
    fun render(path: String): Bitmap {
        check(OpenCVLoader.initLocal()) { "图像处理组件未能加载" }
        val original = decodePhotoBitmap(path) ?: error("无法读取照片")
        try {
            // Keep every label and diagram; the user selects the question in the crop UI next.
            val page = PerspectiveRenderer.renderPage(original, Geometry.fullFrame, rectifyBook = true)
            return try {
                ColorDocumentEnhancer.apply(page.bitmap, page.boundary, book = true)
            } finally { page.bitmap.recycle() }
        } finally { original.recycle() }
    }
}
