package com.moge.app.ui.photo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal fun Modifier.cropGestures(
    state: CropViewportState, saving: Boolean, motionEnabled: Boolean,
    contentInset: Offset = Offset.Zero,
): Modifier = pointerInput(state, state.viewport, saving, motionEnabled, contentInset) {
    if (saving) return@pointerInput
    val handleRadius = 30.dp.toPx()
    val minimumSize = 24.dp.toPx()
    val zoomRampDistance = 24.dp.toPx()
    coroutineScope {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            state.beginGesture()
            var mode = cropDragMode(first.position - contentInset, state.cropRect, handleRadius)
            var multiTouch = false
            val grabOffset = mode.handlePosition(state.cropRect) - (first.position - contentInset)
            var target = mode.handlePosition(state.cropRect)
            var pullingOutwards = false
            val expansion = launch {
                var previous = withFrameNanos { it }
                while (true) {
                    val now = withFrameNanos { it }
                    if (!multiTouch && pullingOutwards && mode.isResize) {
                        state.expandAtEdge(mode, target, (now - previous) / 1_000_000_000f, zoomRampDistance, minimumSize)
                    }
                    previous = now
                }
            }
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        multiTouch = true
                        val zoom = event.calculateZoom().takeIf { it.isFinite() && it > 0f } ?: 1f
                        val pan = event.calculatePan()
                        // Pan already contains the centroid's movement in this event.
                        val focus = event.calculateCentroid(useCurrent = false) - contentInset
                        if (pan.x.isFinite() && pan.y.isFinite() && focus.x.isFinite() && focus.y.isFinite()) {
                            state.transform(zoom, pan, focus)
                        }
                    } else {
                        if (multiTouch) mode = CropDragMode.PAN_IMAGE
                        val change = pressed.first()
                        val delta = change.position - change.previousPosition
                        if (delta.x.isFinite() && delta.y.isFinite()) {
                            if (mode.isResize) {
                                target = change.position - contentInset + grabOffset
                                if (delta != Offset.Zero) pullingOutwards = mode.outwardMovement(delta) > 0f
                                state.dragHandle(mode, target, minimumSize)
                            } else state.drag(mode, delta, minimumSize)
                        }
                    }
                    event.changes.forEach { it.consume() }
                }
            } finally { expansion.cancel() }
            state.finishGesture(motionEnabled)
        }
    }
}
