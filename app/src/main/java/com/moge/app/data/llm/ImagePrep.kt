package com.moge.app.data.llm

import android.graphics.Bitmap
import android.util.Base64
import android.util.Base64OutputStream
import android.util.Log
import com.moge.app.data.image.decodePhotoBitmap
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
        if (maxDim <= 0 || quality !in 0..100) return null
        return runCatching {
            // Allow a final filtered resize, but bound both edge length and decoded pixel count.
            val decodeEdge = (maxDim.toLong() * 2).coerceAtMost(4096).toInt()
            var owned = decodePhotoBitmap(photoPath, maxEdge = decodeEdge) ?: return@runCatching null
            try {
                val longest = maxOf(owned.width, owned.height)
                if (longest > maxDim) {
                    val ratio = maxDim.toDouble() / longest
                    val scaled = Bitmap.createScaledBitmap(
                        owned,
                        (owned.width * ratio).toInt().coerceAtLeast(1),
                        (owned.height * ratio).toInt().coerceAtLeast(1),
                        true,
                    )
                    if (scaled !== owned) owned.recycle()
                    owned = scaled
                }
                val output = ByteArrayOutputStream()
                // Stream JPEG bytes into Base64 instead of retaining separate JPEG byte copies.
                Base64OutputStream(output, Base64.NO_WRAP).use { encoded ->
                    check(owned.compress(Bitmap.CompressFormat.JPEG, quality, encoded)) { "图片压缩失败" }
                }
                output.toString(Charsets.US_ASCII.name())
            } finally {
                // Includes resize/compression failures; this bitmap has never been displayed.
                owned.recycle()
            }
        }.onFailure { Log.w("MogePhoto", "Photo encoding failed", it) }.getOrNull()
    }
}
