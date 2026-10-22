package com.moge.app.imaging.book

import android.graphics.Bitmap
import com.moge.app.data.image.decodePhotoBitmap
import org.opencv.android.OpenCVLoader
import javax.inject.Inject

/** Full-resolution color cleanup only; photographed geometry and faint source ink stay available. */
internal class BookPageProcessor @Inject constructor() {
    fun render(path: String, checkpoint: () -> Unit = {}): Bitmap {
        checkpoint()
        check(OpenCVLoader.initLocal()) { "图像处理组件未能加载" }
        val original = decodePhotoBitmap(path, preserveResolution = true) ?: error("无法以原始分辨率读取照片")
        return try {
            checkpoint()
            ColorDocumentEnhancer.apply(original, book = true, checkpoint = checkpoint)
        } finally { original.recycle() }
    }
}
