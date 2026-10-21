package com.moge.app.data.image

import kotlin.math.roundToInt

/** Fractions refer to the upright photo after [quarterTurns], never to preview pixels. */
data class PhotoCropEdit(
    val quarterTurns: Int = 0,
    val left: Float = 0f,
    val top: Float = 0f,
    val width: Float = 1f,
    val height: Float = 1f,
) {
    init {
        require(listOf(left, top, width, height).all { it.isFinite() })
        require(left >= 0f && top >= 0f && width > 0f && height > 0f)
        require(left + width <= 1.0001f && top + height <= 1.0001f)
    }

    val isIdentity: Boolean get() = Math.floorMod(quarterTurns, 4) == 0 &&
        left == 0f && top == 0f && width == 1f && height == 1f

    fun bounds(sourceWidth: Int, sourceHeight: Int): PhotoCropRegion {
        require(sourceWidth > 0 && sourceHeight > 0)
        val x = (left * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1)
        val y = (top * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1)
        val w = (width * sourceWidth).roundToInt().coerceIn(1, sourceWidth - x)
        val h = (height * sourceHeight).roundToInt().coerceIn(1, sourceHeight - y)
        return PhotoCropRegion(x, y, w, h)
    }
}

data class PhotoCropRegion(val left: Int, val top: Int, val width: Int, val height: Int)

/** A durable worker can finish this edit even after its crop screen has gone away. */
data class PreparedPhotoCrop(val source: String, val fingerprint: String, val edit: PhotoCropEdit)
