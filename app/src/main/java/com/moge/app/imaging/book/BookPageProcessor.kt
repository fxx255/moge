package com.moge.app.imaging.book

import android.graphics.Bitmap
import com.moge.app.data.image.decodePhotoBitmap
import org.opencv.android.OpenCVLoader
import javax.inject.Inject

/** PaperScan's BOOK_FLATTEN pipeline: geometry once, followed by color-preserving cleanup. */
internal class BookPageProcessor @Inject constructor() {
    fun render(path: String): Bitmap {
        check(OpenCVLoader.initLocal()) { "图像处理组件未能加载" }
        val original = decodePhotoBitmap(path, preserveResolution = true) ?: error("无法以原始分辨率读取照片")
        val page = try {
            // Keep every label and diagram; the user selects the question in the crop UI next.
            PerspectiveRenderer.renderPage(original, Geometry.fullFrame, rectifyBook = true)
        } finally { original.recycle() }
        return try {
            ColorDocumentEnhancer.apply(page.bitmap, page.boundary, book = true)
        } finally { page.bitmap.recycle() }
    }
}
