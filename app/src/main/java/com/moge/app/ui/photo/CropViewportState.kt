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

// Leave a deliberate 10% overshrink margin before discarding a partial selection.
private const val WHOLE_PHOTO_RESET_FRACTION = .9f

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
    private var selectionFollowsPhoto = false
    private var pendingFit: FitReason? = null

    private enum class FitReason(val durationMillis: Int) {
        FRAME_ADJUSTMENT(180),
        WHOLE_PHOTO_RESET(120),
    }

    val zoom get() = framing.zoom
    val imageOffset get() = framing.imageOffset
    val cropRect get() = framing.cropRect

    fun reset(image: IntSize) {
        interrupt()
        this.image = image
        maximumZoom = 8f
        selectionFollowsPhoto = false
        pendingFit = null
        framing = initialCropFraming(image, viewport)
    }

    fun resize(size: IntSize) {
        if (size == IntSize.Zero || size == viewport) return
        interrupt()
        selectionFollowsPhoto = false
        pendingFit = null
        val previous = viewport
        viewport = size
        framing = if (previous == IntSize.Zero || cropRect == Rect.Zero) initialCropFraming(image, size)
        else framing.copy(
            imageOffset = Offset(imageOffset.x * size.width / previous.width, imageOffset.y * size.height / previous.height),
            cropRect = scaleRect(cropRect, previous, size),
        )
    }

    fun interrupt() { animation?.cancel(); animation = null }

    fun beginGesture() {
        interrupt()
        selectionFollowsPhoto = false
    }

    fun transform(gestureZoom: Float, pan: Offset, focus: Offset) {
        // Permit continuous pinching below the fitted size. The tiny floor only
        // keeps geometry representable; it is far below a useful viewing size.
        val wholePhotoZoom = minimumCropZoom(image, viewport)
        val minimumZoom = wholePhotoZoom * .001f
        val newZoom = (zoom * gestureZoom).coerceIn(minimumZoom, maximumZoom)
        if (newZoom == zoom && pan == Offset.Zero) return
        if (newZoom < zoom && newZoom <= wholePhotoZoom * WHOLE_PHOTO_RESET_FRACTION) {
            // Shrinking deliberately beyond the whole-photo fitted size resets.
            // Continue following the pinch below it; fitSelection provides
            // the short recovery animation only after the fingers lift.
            framing = CropFraming(newZoom, Offset.Zero, displayedImageRect(image, viewport, newZoom, Offset.Zero))
            selectionFollowsPhoto = true
            pendingFit = FitReason.WHOLE_PHOTO_RESET
            return
        }
        // After shrinking, keep incidental pan and small scale reversals linked
        // for this gesture. Enlarging past a filled frame resumes normal zoom.
        selectionFollowsPhoto = newZoom < zoom ||
            (selectionFollowsPhoto && newZoom <= fitCropFraming(framing, viewport).zoom)
        if (selectionFollowsPhoto) {
            framing = transformLinkedCropFraming(framing, viewport, newZoom, pan, focus)
            // A manual pinch cancels frame fitting. Only a deliberate whole-photo
            // reset should recover when released, including small pinch reversals.
            if (pendingFit != FitReason.WHOLE_PHOTO_RESET || !isWholePhotoSelected(image, viewport, framing)) {
                pendingFit = null
            }
            return
        }
        pendingFit = null
        val applied = newZoom / zoom
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val offset = constrainImageOffset(imageOffset * applied + (focus - center) * (1f - applied) + pan,
            image, viewport, newZoom, cropRect)
        framing = CropFraming(newZoom, offset,
            constrainCropRectToBounds(cropRect, visibleImageBounds(image, viewport, newZoom, offset)))
    }

    fun drag(mode: CropDragMode, delta: Offset, minimumSize: Float) {
        if (delta == Offset.Zero) return
        if (mode == CropDragMode.PAN_IMAGE && selectionFollowsPhoto) {
            // One finger can lift before the other at the end of a pinch.
            framing = transformLinkedCropFraming(framing, viewport, zoom, delta, cropRect.center)
            return
        }
        selectionFollowsPhoto = false
        val bounds = visibleImageBounds(image, viewport, zoom, imageOffset)
        val previous = framing
        framing = when (mode) {
            CropDragMode.PAN_IMAGE -> framing.copy(
                imageOffset = constrainImageOffset(imageOffset + delta, image, viewport, zoom, cropRect))
            CropDragMode.MOVE_CROP -> framing.copy(cropRect = moveCropRect(cropRect, delta, bounds))
            else -> framing.copy(cropRect = resizeCropRectWithinBounds(cropRect, mode, delta, bounds, minimumSize))
        }
        if (framing != previous) {
            pendingFit = if (mode == CropDragMode.PAN_IMAGE) null else FitReason.FRAME_ADJUSTMENT
        }
    }

    fun dragHandle(mode: CropDragMode, target: Offset, minimumSize: Float) {
        drag(mode, target - mode.handlePosition(cropRect), minimumSize)
    }

    fun expandAtEdge(mode: CropDragMode, target: Offset, elapsedSeconds: Float, zoomRampDistance: Float, minimumSize: Float) {
        val expanded = expandCropAtEdge(framing, image, viewport, mode, target, elapsedSeconds, zoomRampDistance, minimumSize)
        if (expanded != framing) {
            framing = expanded
            pendingFit = FitReason.FRAME_ADJUSTMENT
        }
    }

    fun finishGesture(motionEnabled: Boolean) {
        // Keep a manually shrunk frame as-is until it is actually adjusted.
        // A fresh touch can pause a pending fit; a tap then resumes that fit.
        val reason = pendingFit ?: return
        fitSelection(motionEnabled, durationMillis = reason.durationMillis)
    }

    private fun fitSelection(motionEnabled: Boolean, durationMillis: Int) {
        interrupt()
        selectionFollowsPhoto = false
        val from = framing
        val target = fitCropFraming(from, viewport)
        if (target == from) {
            pendingFit = null
            return
        }
        maximumZoom = maxOf(maximumZoom, target.zoom)
        if (!motionEnabled) {
            framing = target
            pendingFit = null
        }
        else animation = scope.launch {
            animate(0f, 1f, animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)) { progress, _ ->
                framing = interpolateCropFraming(from, target, progress)
            }
            framing = target
            pendingFit = null
            animation = null
        }
    }
}
