package com.moge.app.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/** Image and frame move together; reframing must never change the selected source pixels. */
internal data class CropFraming(val zoom: Float, val imageOffset: Offset, val cropRect: Rect)

// Three percent on either side leaves room to pull a handle beyond a fitted selection.
internal const val CROP_FIT_FRACTION = .94f

internal fun initialCropFraming(image: IntSize, viewport: IntSize): CropFraming {
    val zoom = minimumCropZoom(image, viewport) * CROP_FIT_FRACTION
    return CropFraming(zoom, Offset.Zero, visibleImageBounds(image, viewport, zoom, Offset.Zero))
}

/** Rotate both photo and selection about the viewport center, retaining the same source pixels. */
internal fun rotateCropFramingCounterclockwise(
    current: CropFraming, image: IntSize, viewport: IntSize,
): CropFraming {
    val rotatedImage = IntSize(image.height, image.width)
    if (image.width <= 0 || image.height <= 0 || viewport.width <= 0 || viewport.height <= 0 ||
        current.cropRect.width <= 0f || current.cropRect.height <= 0f) {
        return initialCropFraming(rotatedImage, viewport)
    }
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    val crop = current.cropRect
    val rotated = CropFraming(
        zoom = current.zoom * coverScale(image, viewport) / coverScale(rotatedImage, viewport),
        imageOffset = Offset(current.imageOffset.y, -current.imageOffset.x),
        cropRect = Rect(center.x + crop.top - center.y, center.y - crop.right + center.x,
            center.x + crop.bottom - center.y, center.y - crop.left + center.x),
    )
    // A rectangular viewport may need to shrink or translate both layers to keep the rotated frame reachable.
    val factor = minOf(1f, viewport.width / rotated.cropRect.width, viewport.height / rotated.cropRect.height)
    return transformLinkedCropFraming(rotated, viewport, rotated.zoom * factor, Offset.Zero, center)
}

/** Transform the photo and selection together while the frame fits inside the viewport. */
internal fun transformLinkedCropFraming(
    current: CropFraming, viewport: IntSize, zoom: Float, pan: Offset, focus: Offset,
): CropFraming {
    val factor = zoom / current.zoom
    val center = Offset(viewport.width / 2f, viewport.height / 2f)
    val imageOffset = current.imageOffset * factor + (focus - center) * (1f - factor) + pan
    val crop = Rect(focus + (current.cropRect.topLeft - focus) * factor + pan,
        focus + (current.cropRect.bottomRight - focus) * factor + pan)
    // Keep the frame accessible without clipping it. Translate BOTH
    // photo and frame by the same amount so their source-pixel mapping survives.
    val left = crop.left.coerceIn(0f, (viewport.width - crop.width).coerceAtLeast(0f))
    val top = crop.top.coerceIn(0f, (viewport.height - crop.height).coerceAtLeast(0f))
    val correction = Offset(left - crop.left, top - crop.top)
    return CropFraming(zoom, imageOffset + correction, crop.translate(correction))
}

/** Fit the current selection into the viewport, retaining its free aspect ratio. */
internal fun fitCropFraming(current: CropFraming, viewport: IntSize): CropFraming {
    val crop = current.cropRect
    if (viewport.width <= 0 || viewport.height <= 0 || crop.width <= 0f || crop.height <= 0f) return current
    val factor = min(viewport.width / crop.width, viewport.height / crop.height) * CROP_FIT_FRACTION
    if (!factor.isFinite() || factor <= 0f || kotlin.math.abs(factor - 1f) <= .001f ||
        !(current.zoom * factor).isFinite()) return current
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
