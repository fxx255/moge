package com.moge.app.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp

internal val CropDragMode.isResize: Boolean get() = this != CropDragMode.PAN_IMAGE && this != CropDragMode.MOVE_CROP
private val CropDragMode.left: Boolean get() = this == CropDragMode.LEFT_EDGE || this == CropDragMode.TOP_LEFT || this == CropDragMode.BOTTOM_LEFT
private val CropDragMode.right: Boolean get() = this == CropDragMode.RIGHT_EDGE || this == CropDragMode.TOP_RIGHT || this == CropDragMode.BOTTOM_RIGHT
private val CropDragMode.top: Boolean get() = this == CropDragMode.TOP_EDGE || this == CropDragMode.TOP_LEFT || this == CropDragMode.TOP_RIGHT
private val CropDragMode.bottom: Boolean get() = this == CropDragMode.BOTTOM_EDGE || this == CropDragMode.BOTTOM_LEFT || this == CropDragMode.BOTTOM_RIGHT

internal fun CropDragMode.handlePosition(crop: Rect): Offset = Offset(
    when { left -> crop.left; right -> crop.right; else -> crop.center.x },
    when { top -> crop.top; bottom -> crop.bottom; else -> crop.center.y },
)

/** Positive means pulling a controlled edge outwards; zero preserves a stationary hold's intent. */
internal fun CropDragMode.outwardMovement(delta: Offset): Float = maxOf(
    when { left -> -delta.x; right -> delta.x; else -> 0f },
    when { top -> -delta.y; bottom -> delta.y; else -> 0f },
)

internal fun resizeCropToHandle(crop: Rect, mode: CropDragMode, target: Offset, bounds: Rect, minimumSize: Float): Rect {
    val difference = target - mode.handlePosition(crop)
    return resizeCropRectWithinBounds(crop, mode, difference, bounds, minimumSize)
}

/** Reveal hidden source pixels with a time-based zoom, anchored at the untouched edge/corner. */
internal fun expandCropAtEdge(
    current: CropFraming, image: IntSize, viewport: IntSize, mode: CropDragMode,
    target: Offset, elapsedSeconds: Float, edgeZone: Float, minimumSize: Float,
): CropFraming {
    if (!mode.isResize || edgeZone <= 0f || elapsedSeconds <= 0f || current.cropRect == Rect.Zero) return current
    val crop = current.cropRect
    val full = displayedImageRect(image, viewport, current.zoom, current.imageOffset)
    fun pressure(distance: Float) = (1f - distance / edgeZone).coerceIn(0f, 1f)
    // Stop at the real image boundary, even if a finger keeps pulling out of the viewport.
    val horizontal = when {
        mode.left && crop.left > full.left + .01f && target.x <= crop.left + .5f -> pressure(target.x)
        mode.right && crop.right < full.right - .01f && target.x >= crop.right - .5f -> pressure(viewport.width - target.x)
        else -> 0f
    }
    val vertical = when {
        mode.top && crop.top > full.top + .01f && target.y <= crop.top + .5f -> pressure(target.y)
        mode.bottom && crop.bottom < full.bottom - .01f && target.y >= crop.bottom - .5f -> pressure(viewport.height - target.y)
        else -> 0f
    }
    val strength = maxOf(horizontal, vertical)
    if (strength == 0f) return current
    val factor = exp(-.28f * strength * elapsedSeconds.coerceAtMost(.05f))
    val pivot = Offset(
        when { mode.left -> crop.right; mode.right -> crop.left; else -> crop.center.x },
        when { mode.top -> crop.bottom; mode.bottom -> crop.top; else -> crop.center.y },
    )
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    val zoom = current.zoom * factor
    val offset = current.imageOffset * factor + (pivot - center) * (1f - factor)
    val scaled = Rect(pivot + (crop.topLeft - pivot) * factor, pivot + (crop.bottomRight - pivot) * factor)
    return CropFraming(zoom, offset, resizeCropToHandle(scaled, mode, target,
        visibleImageBounds(image, viewport, zoom, offset), minimumSize))
}
