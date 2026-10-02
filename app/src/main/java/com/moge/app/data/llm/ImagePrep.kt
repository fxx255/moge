package com.moge.app.data.llm

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream

/** 把照片压缩成适合发给多模态模型的 base64 JPEG。 */
object ImagePrep {

    /**
     * 降采样 + 缩放到最大边 [maxDim]，再按 [quality] 压成 JPEG base64。
     * 读取失败返回 null。
     *
     * ⚠️ 先做**显式存在性检查**再交给 `BitmapFactory`：`decodeFile` 对不存在的
     * 路径本应返回 null，但某些运行环境（Robolectric 的假实现就是一个）会返回
     * 一张合成位图 —— 于是「附件已丢失」会被悄悄编码成一张假图发给模型，
     * 用户拿到的是基于不存在内容的回答。这里无论如何都先自己确认文件可用。
     */
    fun encodeForVision(photoPath: String, maxDim: Int = 2048, quality: Int = 95): String? {
        val file = java.io.File(photoPath)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(photoPath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDim * 2) sample *= 2
        val decoded = BitmapFactory.decodeFile(photoPath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@runCatching null
        val oriented = applyExifOrientation(decoded, photoPath)
        if (oriented !== decoded) decoded.recycle()

        val longest = maxOf(oriented.width, oriented.height)
        val scaled = if (longest > maxDim) {
            val ratio = maxDim.toFloat() / longest
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * ratio).toInt().coerceAtLeast(1),
                (oriented.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            oriented
        }

        try {
            val output = ByteArrayOutputStream()
            check(scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "图片压缩失败" }
            Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } finally {
            if (scaled !== oriented) scaled.recycle()
            oriented.recycle()
        }
        }.getOrNull()
    }

    private fun applyExifOrientation(source: Bitmap, photoPath: String): Bitmap {
        val orientation = runCatching {
            ExifInterface(photoPath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
