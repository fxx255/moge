package com.moge.app.ui.photo

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Owns only the crop viewport interaction and its interruptible reframe animation. */
@Stable
internal class CropViewportState(private val scope: CoroutineScope) {
    var viewport by mutableStateOf(IntSize.Zero)
        private set
    var framing by mutableStateOf(CropFraming(1f, Offset.Zero, Rect.Zero))
        private set
    private var image = IntSize.Zero
    private var animation: Job? = null
    private var maximumZoom = 8f
    val zoom get() = framing.zoom
    val imageOffset get() = framing.imageOffset
    val cropRect get() = framing.cropRect

    fun reset(image: IntSize) {
        interrupt()
        this.image = image
        maximumZoom = 8f
        framing = initialCropFraming(image, viewport)
    }

    fun resize(size: IntSize) {
        if (size == IntSize.Zero || size == viewport) return
        interrupt()
        val previous = viewport
        viewport = size
        framing = if (previous == IntSize.Zero || cropRect == Rect.Zero) initialCropFraming(image, size)
        else framing.copy(
            imageOffset = Offset(imageOffset.x * size.width / previous.width, imageOffset.y * size.height / previous.height),
            cropRect = scaleRect(cropRect, previous, size),
        )
    }

    fun interrupt() { animation?.cancel(); animation = null }

    fun transform(gestureZoom: Float, pan: Offset, focus: Offset) {
        val minimumZoom = minimumCropZoom(image, viewport)
        val newZoom = (zoom * gestureZoom).coerceIn(minimumZoom, maximumZoom)
        val applied = newZoom / zoom
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val offset = if (newZoom <= minimumZoom) Offset.Zero
        else constrainImageOffset(imageOffset * applied + (focus - center) * (1f - applied) + pan,
            image, viewport, newZoom, cropRect)
        framing = CropFraming(newZoom, offset,
            constrainCropRectToBounds(cropRect, visibleImageBounds(image, viewport, newZoom, offset)))
    }

    fun drag(mode: CropDragMode, delta: Offset, minimumSize: Float) {
        val bounds = visibleImageBounds(image, viewport, zoom, imageOffset)
        framing = when (mode) {
            CropDragMode.PAN_IMAGE -> framing.copy(
                imageOffset = constrainImageOffset(imageOffset + delta, image, viewport, zoom, cropRect))
            CropDragMode.MOVE_CROP -> framing.copy(cropRect = moveCropRect(cropRect, delta, bounds))
            else -> framing.copy(cropRect = resizeCropRectWithinBounds(cropRect, mode, delta, bounds, minimumSize))
        }
    }

    fun fitSelection(motionEnabled: Boolean) {
        interrupt()
        val from = framing
        val target = fitCropFraming(from, viewport)
        if (target == from) return
        maximumZoom = maxOf(maximumZoom, target.zoom)
        if (!motionEnabled) framing = target
        else animation = scope.launch {
            animate(0f, 1f, animationSpec = tween(180, easing = FastOutSlowInEasing)) { progress, _ ->
                framing = interpolateCropFraming(from, target, progress)
            }
            framing = target
        }
    }
}
