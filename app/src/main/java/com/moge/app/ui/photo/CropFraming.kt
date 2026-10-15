package com.moge.app.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/** Image and frame move together; reframing must never change the selected source pixels. */
internal data class CropFraming(val zoom: Float, val imageOffset: Offset, val cropRect: Rect)

internal fun initialCropFraming(image: IntSize, viewport: IntSize): CropFraming {
    val zoom = minimumCropZoom(image, viewport)
    return CropFraming(zoom, Offset.Zero, visibleImageBounds(image, viewport, zoom, Offset.Zero))
}

/** Fit the current selection into the viewport, retaining its free aspect ratio. */
internal fun fitCropFraming(current: CropFraming, viewport: IntSize): CropFraming {
    val crop = current.cropRect
    if (viewport.width <= 0 || viewport.height <= 0 || crop.width <= 0f || crop.height <= 0f) return current
    val factor = min(viewport.width / crop.width, viewport.height / crop.height)
    if (!factor.isFinite() || factor <= 1.001f || !(current.zoom * factor).isFinite()) return current
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    return CropFraming(
        zoom = current.zoom * factor,
        imageOffset = (center + current.imageOffset - crop.center) * factor,
        cropRect = Rect(center - Offset(crop.width, crop.height) * factor / 2f,
            center + Offset(crop.width, crop.height) * factor / 2f),
    )
}

internal fun interpolateCropFraming(from: CropFraming, to: CropFraming, progress: Float) = CropFraming(
    zoom = androidx.compose.ui.util.lerp(from.zoom, to.zoom, progress),
    imageOffset = androidx.compose.ui.geometry.lerp(from.imageOffset, to.imageOffset, progress),
    cropRect = androidx.compose.ui.geometry.lerp(from.cropRect, to.cropRect, progress),
)

internal fun isWholePhotoSelected(image: IntSize, viewport: IntSize, framing: CropFraming): Boolean {
    if (image.width <= 0 || image.height <= 0 || framing.cropRect == Rect.Zero) return false
    val full = displayedImageRect(image, viewport, framing.zoom, framing.imageOffset)
    val crop = framing.cropRect
    return listOf(full.left - crop.left, full.top - crop.top, full.right - crop.right, full.bottom - crop.bottom)
        .all { kotlin.math.abs(it) < .01f }
}
