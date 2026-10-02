package com.moge.app.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 裁剪器的纯几何：视口坐标里的选框 ↔ 原图像素，以及选框的移动 / 缩放约束。
 * 从砺行 PhotoCropDialog 拆出来，和界面分开测试。
 *
 * 坐标约定：照片以「cover」方式铺满视口（baseScale = max(视口/照片)），再乘 [zoom]、平移 imageOffset。
 */
internal data class CropRatio(val label: String, val value: Float?)

internal val cropRatios = listOf(
    CropRatio("自由", null),
    CropRatio("1:1", 1f),
    CropRatio("4:3", 4f / 3f),
    CropRatio("3:4", 3f / 4f),
    CropRatio("16:9", 16f / 9f),
    CropRatio("3:1", 3f),
)

internal enum class CropDragMode {
    PAN_IMAGE,
    MOVE_CROP,
    TOP_LEFT,
    TOP_EDGE,
    TOP_RIGHT,
    RIGHT_EDGE,
    BOTTOM_RIGHT,
    BOTTOM_EDGE,
    BOTTOM_LEFT,
    LEFT_EDGE,
}

/** 视口尺寸变化时把选框按比例迁移到新坐标系（旋转、分屏后不再错位）。 */
internal fun scaleRect(rect: Rect, from: IntSize, to: IntSize): Rect {
    if (from.width <= 0 || from.height <= 0 || to.width <= 0 || to.height <= 0) return rect
    val scaleX = to.width.toFloat() / from.width
    val scaleY = to.height.toFloat() / from.height
    return Rect(rect.left * scaleX, rect.top * scaleY, rect.right * scaleX, rect.bottom * scaleY)
}

/** 最小缩放：缩到整张照片恰好完整可见（极端长条图也能框到全图）。 */
internal fun minimumCropZoom(previewSize: IntSize, viewport: IntSize): Float {
    if (previewSize.width <= 0 || previewSize.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return 1f
    val widthScale = viewport.width.toFloat() / previewSize.width
    val heightScale = viewport.height.toFloat() / previewSize.height
    val coverScale = max(widthScale, heightScale)
    val fitScale = min(widthScale, heightScale)
    return (fitScale / coverScale).coerceIn(0.01f, 1f)
}

private fun coverScale(previewSize: IntSize, viewport: IntSize): Float = max(
    viewport.width.toFloat() / previewSize.width,
    viewport.height.toFloat() / previewSize.height,
)

/** 视口里实际有照片像素的区域，不含缩小后露出的黑边。 */
internal fun visibleImageBounds(previewSize: IntSize, viewport: IntSize, zoom: Float, imageOffset: Offset): Rect {
    if (previewSize.width <= 0 || previewSize.height <= 0 || viewport.width <= 0 || viewport.height <= 0) {
        return Rect.Zero
    }
    val image = displayedImageRect(previewSize, viewport, zoom, imageOffset)
    return Rect(
        left = max(0f, image.left),
        top = max(0f, image.top),
        right = min(viewport.width.toFloat(), image.right),
        bottom = min(viewport.height.toFloat(), image.bottom),
    )
}

/** 照片在视口坐标里的完整外框（可能超出视口）。 */
internal fun displayedImageRect(previewSize: IntSize, viewport: IntSize, zoom: Float, imageOffset: Offset): Rect {
    val scale = coverScale(previewSize, viewport) * zoom
    val width = previewSize.width * scale
    val height = previewSize.height * scale
    val left = (viewport.width - width) / 2f + imageOffset.x
    val top = (viewport.height - height) / 2f + imageOffset.y
    return Rect(left, top, left + width, top + height)
}

/** 平移限制：放大时不能把照片拖出视口；缩小到比视口还小时居中。 */
internal fun constrainImageOffset(requested: Offset, previewSize: IntSize, viewport: IntSize, zoom: Float): Offset {
    if (viewport == IntSize.Zero || previewSize.width <= 0 || previewSize.height <= 0) return Offset.Zero
    val scale = coverScale(previewSize, viewport) * zoom
    val maxX = ((previewSize.width * scale - viewport.width) / 2f).coerceAtLeast(0f)
    val maxY = ((previewSize.height * scale - viewport.height) / 2f).coerceAtLeast(0f)
    return Offset(requested.x.coerceIn(-maxX, maxX), requested.y.coerceIn(-maxY, maxY))
}

internal fun centeredCropRect(viewport: IntSize, ratio: Float?, freeRatio: Float = 1f): Rect {
    if (viewport == IntSize.Zero) return Rect.Zero
    val targetRatio = (ratio ?: freeRatio).coerceIn(0.05f, 20f)
    val maxWidth = viewport.width * 0.86f
    val maxHeight = viewport.height * 0.86f
    val width: Float
    val height: Float
    if (maxWidth / maxHeight > targetRatio) {
        height = maxHeight
        width = height * targetRatio
    } else {
        width = maxWidth
        height = width / targetRatio
    }
    val left = (viewport.width - width) / 2f
    val top = (viewport.height - height) / 2f
    return Rect(left, top, left + width, top + height)
}

internal fun cropDragMode(position: Offset, rect: Rect, handleRadius: Float): CropDragMode {
    fun near(point: Offset): Boolean = (position - point).getDistance() <= handleRadius
    return when {
        near(rect.topLeft) -> CropDragMode.TOP_LEFT
        near(rect.topRight) -> CropDragMode.TOP_RIGHT
        near(rect.bottomLeft) -> CropDragMode.BOTTOM_LEFT
        near(rect.bottomRight) -> CropDragMode.BOTTOM_RIGHT
        near(Offset(rect.center.x, rect.top)) -> CropDragMode.TOP_EDGE
        near(Offset(rect.right, rect.center.y)) -> CropDragMode.RIGHT_EDGE
        near(Offset(rect.center.x, rect.bottom)) -> CropDragMode.BOTTOM_EDGE
        near(Offset(rect.left, rect.center.y)) -> CropDragMode.LEFT_EDGE
        rect.contains(position) -> CropDragMode.MOVE_CROP
        else -> CropDragMode.PAN_IMAGE
    }
}

internal fun constrainCropRectToBounds(rect: Rect, bounds: Rect, ratio: Float?): Rect {
    if (rect == Rect.Zero || bounds.width <= 0f || bounds.height <= 0f) return rect
    val width: Float
    val height: Float
    if (ratio == null) {
        width = min(rect.width, bounds.width)
        height = min(rect.height, bounds.height)
    } else {
        val safeRatio = ratio.coerceAtLeast(0.01f)
        var candidateWidth = min(rect.width, bounds.width)
        var candidateHeight = candidateWidth / safeRatio
        if (candidateHeight > bounds.height) {
            candidateHeight = bounds.height
            candidateWidth = candidateHeight * safeRatio
        }
        width = candidateWidth
        height = candidateHeight
    }
    val left = (rect.center.x - width / 2f).coerceIn(bounds.left, bounds.right - width)
    val top = (rect.center.y - height / 2f).coerceIn(bounds.top, bounds.bottom - height)
    return Rect(left, top, left + width, top + height)
}

internal fun moveCropRect(rect: Rect, delta: Offset, bounds: Rect): Rect {
    if (bounds.width <= 0f || bounds.height <= 0f) return rect
    val dx = delta.x.coerceIn(bounds.left - rect.left, bounds.right - rect.right)
    val dy = delta.y.coerceIn(bounds.top - rect.top, bounds.bottom - rect.bottom)
    return rect.translate(Offset(dx, dy))
}

internal fun resizeCropRectWithinBounds(
    rect: Rect,
    mode: CropDragMode,
    delta: Offset,
    bounds: Rect,
    ratio: Float?,
    minimumSize: Float,
): Rect {
    if (bounds.width <= 0f || bounds.height <= 0f) return rect
    val localRect = rect.translate(-bounds.topLeft)
    val localViewport = IntSize(bounds.width.toInt().coerceAtLeast(1), bounds.height.toInt().coerceAtLeast(1))
    val effectiveMinimum = min(minimumSize, min(bounds.width, bounds.height)).coerceAtLeast(1f)
    val resized = resizeCropRect(localRect, mode, delta, localViewport, ratio, effectiveMinimum)
        .translate(bounds.topLeft)
    return constrainCropRectToBounds(resized, bounds, ratio)
}

private fun resizeCropRect(
    rect: Rect,
    mode: CropDragMode,
    delta: Offset,
    viewport: IntSize,
    ratio: Float?,
    minimumSize: Float,
): Rect {
    if (ratio == null) {
        return when (mode) {
            CropDragMode.TOP_LEFT -> Rect(
                (rect.left + delta.x).coerceIn(0f, rect.right - minimumSize),
                (rect.top + delta.y).coerceIn(0f, rect.bottom - minimumSize),
                rect.right,
                rect.bottom,
            )
            CropDragMode.TOP_RIGHT -> Rect(
                rect.left,
                (rect.top + delta.y).coerceIn(0f, rect.bottom - minimumSize),
                (rect.right + delta.x).coerceIn(rect.left + minimumSize, viewport.width.toFloat()),
                rect.bottom,
            )
            CropDragMode.BOTTOM_LEFT -> Rect(
                (rect.left + delta.x).coerceIn(0f, rect.right - minimumSize),
                rect.top,
                rect.right,
                (rect.bottom + delta.y).coerceIn(rect.top + minimumSize, viewport.height.toFloat()),
            )
            CropDragMode.BOTTOM_RIGHT -> Rect(
                rect.left,
                rect.top,
                (rect.right + delta.x).coerceIn(rect.left + minimumSize, viewport.width.toFloat()),
                (rect.bottom + delta.y).coerceIn(rect.top + minimumSize, viewport.height.toFloat()),
            )
            CropDragMode.TOP_EDGE -> Rect(
                rect.left,
                (rect.top + delta.y).coerceIn(0f, rect.bottom - minimumSize),
                rect.right,
                rect.bottom,
            )
            CropDragMode.RIGHT_EDGE -> Rect(
                rect.left,
                rect.top,
                (rect.right + delta.x).coerceIn(rect.left + minimumSize, viewport.width.toFloat()),
                rect.bottom,
            )
            CropDragMode.BOTTOM_EDGE -> Rect(
                rect.left,
                rect.top,
                rect.right,
                (rect.bottom + delta.y).coerceIn(rect.top + minimumSize, viewport.height.toFloat()),
            )
            CropDragMode.LEFT_EDGE -> Rect(
                (rect.left + delta.x).coerceIn(0f, rect.right - minimumSize),
                rect.top,
                rect.right,
                rect.bottom,
            )
            else -> rect
        }
    }

    if (mode == CropDragMode.TOP_EDGE || mode == CropDragMode.BOTTOM_EDGE) {
        val centerX = rect.center.x
        val requestedHeight = if (mode == CropDragMode.TOP_EDGE) rect.height - delta.y else rect.height + delta.y
        val verticalLimit = if (mode == CropDragMode.TOP_EDGE) rect.bottom else viewport.height - rect.top
        val horizontalLimit = 2f * min(centerX, viewport.width - centerX) / ratio
        val maximumHeight = min(verticalLimit, horizontalLimit)
        val minimumHeight = max(minimumSize, minimumSize / ratio).coerceAtMost(maximumHeight)
        val height = requestedHeight.coerceIn(minimumHeight, maximumHeight)
        val width = height * ratio
        val left = centerX - width / 2f
        return if (mode == CropDragMode.TOP_EDGE) {
            Rect(left, rect.bottom - height, left + width, rect.bottom)
        } else {
            Rect(left, rect.top, left + width, rect.top + height)
        }
    }

    if (mode == CropDragMode.LEFT_EDGE || mode == CropDragMode.RIGHT_EDGE) {
        val centerY = rect.center.y
        val requestedWidth = if (mode == CropDragMode.LEFT_EDGE) rect.width - delta.x else rect.width + delta.x
        val horizontalLimit = if (mode == CropDragMode.LEFT_EDGE) rect.right else viewport.width - rect.left
        val verticalLimit = 2f * min(centerY, viewport.height - centerY) * ratio
        val maximumWidth = min(horizontalLimit, verticalLimit)
        val minimumWidth = max(minimumSize, minimumSize * ratio).coerceAtMost(maximumWidth)
        val width = requestedWidth.coerceIn(minimumWidth, maximumWidth)
        val height = width / ratio
        val top = centerY - height / 2f
        return if (mode == CropDragMode.LEFT_EDGE) {
            Rect(rect.right - width, top, rect.right, top + height)
        } else {
            Rect(rect.left, top, rect.left + width, top + height)
        }
    }

    val anchor = when (mode) {
        CropDragMode.TOP_LEFT -> rect.bottomRight
        CropDragMode.TOP_RIGHT -> rect.bottomLeft
        CropDragMode.BOTTOM_LEFT -> rect.topRight
        CropDragMode.BOTTOM_RIGHT -> rect.topLeft
        else -> return rect
    }
    val moving = when (mode) {
        CropDragMode.TOP_LEFT -> rect.topLeft + delta
        CropDragMode.TOP_RIGHT -> rect.topRight + delta
        CropDragMode.BOTTOM_LEFT -> rect.bottomLeft + delta
        CropDragMode.BOTTOM_RIGHT -> rect.bottomRight + delta
        else -> return rect
    }
    val widthFromX = abs(moving.x - anchor.x)
    val widthFromY = abs(moving.y - anchor.y) * ratio
    val requestedWidth = if (abs(widthFromX - rect.width) >= abs(widthFromY - rect.width)) widthFromX else widthFromY
    val maximumWidth = when (mode) {
        CropDragMode.TOP_LEFT -> min(anchor.x, anchor.y * ratio)
        CropDragMode.TOP_RIGHT -> min(viewport.width - anchor.x, anchor.y * ratio)
        CropDragMode.BOTTOM_LEFT -> min(anchor.x, (viewport.height - anchor.y) * ratio)
        CropDragMode.BOTTOM_RIGHT -> min(viewport.width - anchor.x, (viewport.height - anchor.y) * ratio)
        else -> rect.width
    }
    val minimumWidth = max(minimumSize, minimumSize * ratio).coerceAtMost(maximumWidth)
    val width = requestedWidth.coerceIn(minimumWidth, maximumWidth)
    val height = width / ratio
    return when (mode) {
        CropDragMode.TOP_LEFT -> Rect(anchor.x - width, anchor.y - height, anchor.x, anchor.y)
        CropDragMode.TOP_RIGHT -> Rect(anchor.x, anchor.y - height, anchor.x + width, anchor.y)
        CropDragMode.BOTTOM_LEFT -> Rect(anchor.x - width, anchor.y, anchor.x, anchor.y + height)
        CropDragMode.BOTTOM_RIGHT -> Rect(anchor.x, anchor.y, anchor.x + width, anchor.y + height)
        else -> rect
    }
}

/** 屏幕选框映射回原图像素。 */
internal data class PixelCrop(val left: Int, val top: Int, val width: Int, val height: Int)

internal fun cropBoundsInSource(
    previewSize: IntSize,
    sourceSize: IntSize,
    viewport: IntSize,
    zoom: Float,
    imageOffset: Offset,
    cropRect: Rect,
): PixelCrop {
    require(previewSize.width > 0 && previewSize.height > 0)
    require(sourceSize.width > 0 && sourceSize.height > 0)
    require(viewport.width > 0 && viewport.height > 0)
    require(zoom > 0f)
    val baseScale = max(
        viewport.width.toFloat() / previewSize.width,
        viewport.height.toFloat() / previewSize.height,
    )
    val displayedWidth = previewSize.width * baseScale * zoom
    val displayedHeight = previewSize.height * baseScale * zoom
    val left = (viewport.width - displayedWidth) / 2f + imageOffset.x
    val top = (viewport.height - displayedHeight) / 2f + imageOffset.y

    val cropX = (((cropRect.left - left) / displayedWidth) * sourceSize.width)
        .roundToInt().coerceIn(0, sourceSize.width - 1)
    val cropY = (((cropRect.top - top) / displayedHeight) * sourceSize.height)
        .roundToInt().coerceIn(0, sourceSize.height - 1)
    val cropWidth = ((cropRect.width / displayedWidth) * sourceSize.width)
        .roundToInt().coerceAtLeast(1).coerceAtMost(sourceSize.width - cropX)
    val cropHeight = ((cropRect.height / displayedHeight) * sourceSize.height)
        .roundToInt().coerceAtLeast(1).coerceAtMost(sourceSize.height - cropY)
    return PixelCrop(cropX, cropY, cropWidth, cropHeight)
}
