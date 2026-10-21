package com.moge.app.data.image

import android.graphics.Bitmap
import android.graphics.Rect

internal fun decodeEditedPhoto(path: String, edit: PhotoCropEdit): Bitmap =
    decodePhotoCrop(path, edit.quarterTurns, preserveResolution = true) { width, height ->
        val region = edit.bounds(width, height)
        Rect(region.left, region.top, region.left + region.width, region.top + region.height)
    } ?: error("无法以原始分辨率读取裁剪区域，图片可能过大或格式不受支持")

/** Save only the original; presentation work uses the same edit independently. */
internal fun applyPhotoCrop(path: String, edit: PhotoCropEdit) {
    if (edit.isIdentity) return
    val bitmap = decodeEditedPhoto(path, edit)
    try { writePhotoBitmap(path, bitmap) } finally { bitmap.recycle() }
}
