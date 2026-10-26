package com.moge.app.ui.capture

import android.annotation.SuppressLint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.ui.geometry.Offset

/** A whole two-finger gesture belongs to zoom, including the final finger lifting. */
@SuppressLint("ClickableViewAccessibility")
internal fun installCameraPreviewGestures(
    view: View, label: String, onTap: (Offset) -> Unit,
    onZoomStart: () -> Unit, onZoom: (Float) -> Unit,
): () -> Unit {
    var tap: Offset? = null
    var multiTouch = false
    view.contentDescription = label
    view.isFocusable = true
    view.setOnClickListener {
        val position = tap ?: Offset(view.width / 2f, view.height / 2f)
        tap = null
        onTap(position)
    }
    val detector = GestureDetector(view.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (multiTouch) return false
            tap = Offset(event.x, event.y)
            view.performClick()
            return true
        }
    }).apply { setOnDoubleTapListener(null) }
    val scaleDetector = ScaleGestureDetector(view.context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            onZoomStart()
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            if (factor.isFinite() && factor > 0f) onZoom(factor)
            return true
        }
    }).apply { isQuickScaleEnabled = false; isStylusScaleEnabled = false }
    view.setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
        if (event.pointerCount > 1) multiTouch = true
        scaleDetector.onTouchEvent(event)
        detector.onTouchEvent(event)
        true
    }
    return {
        tap = null
        view.setOnTouchListener(null)
        view.setOnClickListener(null)
        view.contentDescription = null
        view.isFocusable = false
        view.isClickable = false
    }
}
