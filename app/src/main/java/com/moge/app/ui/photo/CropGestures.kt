package com.moge.app.ui.photo

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

internal fun Modifier.cropGestures(state: CropViewportState, saving: Boolean, motionEnabled: Boolean): Modifier =
    pointerInput(state, state.viewport, saving, motionEnabled) {
        if (saving) return@pointerInput
        val handleRadius = 30.dp.toPx()
        val minimumSize = 24.dp.toPx()
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false)
            state.interrupt()
            var mode = cropDragMode(first.position, state.cropRect, handleRadius)
            var multiTouch = false
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                if (pressed.size >= 2) {
                    multiTouch = true
                    val zoom = event.calculateZoom().takeIf { it.isFinite() && it > 0f } ?: 1f
                    val pan = event.calculatePan()
                    val focus = event.calculateCentroid()
                    if (pan.x.isFinite() && pan.y.isFinite() && focus.x.isFinite() && focus.y.isFinite()) {
                        state.transform(zoom, pan, focus)
                    }
                } else {
                    if (multiTouch) mode = CropDragMode.PAN_IMAGE
                    val change = pressed.first()
                    val delta = change.position - change.previousPosition
                    if (delta.x.isFinite() && delta.y.isFinite()) {
                        state.drag(mode, delta, minimumSize)
                    }
                }
                event.changes.forEach { it.consume() }
            }
            // Also resume a fit interrupted by a fresh touch. Already-fitted
            // frames are unchanged, so ordinary panning keeps its manual zoom.
            state.fitSelection(motionEnabled, durationMillis = if (multiTouch) 120 else 180)
        }
    }
