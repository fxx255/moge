package com.moge.app.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.exp

// Reaching the viewport edge is still a normal resize. Require a deliberate
// extra pull of 2.1 percent on that axis before revealing hidden photo pixels.
internal const val CROP_EDGE_OVERSHOOT_FRACTION = .021f

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
    target: Offset, elapsedSeconds: Float, zoomRampDistance: Float, minimumSize: Float,
): CropFraming {
    if (!mode.isResize || zoomRampDistance <= 0f || elapsedSeconds <= 0f || current.cropRect == Rect.Zero) return current
    val crop = current.cropRect
    val full = displayedImageRect(image, viewport, current.zoom, current.imageOffset)
    // Use the unclamped handle target, not its last visible position. The dead
    // zone is stable across frames, and pulling back into it stops zooming.
    fun speed(overshoot: Float, axisSize: Int): Float {
        val pull = ((overshoot - axisSize * CROP_EDGE_OVERSHOOT_FRACTION) / zoomRampDistance).coerceIn(0f, 2f)
        // Keep the gentle onset; a further ramp adds at most 40% to the former top speed.
        return .28f * pull.coerceAtMost(1f) * (1f + .4f * (pull - 1f).coerceAtLeast(0f))
    }
    // Stop at the real image boundary, even if a finger keeps pulling out of the viewport.
    val horizontal = when {
        mode.left && crop.left > full.left + .01f && target.x <= crop.left + .5f -> speed(-target.x, viewport.width)
        mode.right && crop.right < full.right - .01f && target.x >= crop.right - .5f -> speed(target.x - viewport.width, viewport.width)
        else -> 0f
    }
    val vertical = when {
        mode.top && crop.top > full.top + .01f && target.y <= crop.top + .5f -> speed(-target.y, viewport.height)
        mode.bottom && crop.bottom < full.bottom - .01f && target.y >= crop.bottom - .5f -> speed(target.y - viewport.height, viewport.height)
        else -> 0f
    }
    val rate = maxOf(horizontal, vertical)
    if (rate == 0f) return current
    val factor = exp(-rate * elapsedSeconds.coerceAtMost(.05f))
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
