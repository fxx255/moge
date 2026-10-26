package com.moge.app.ui.capture

import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Accumulates pinch deltas while CameraX is still applying an earlier zoom request. */
internal class CameraPreviewZoom(private val executor: Executor) {
    private var camera: Camera? = null
    private var requestedRatio = 1f
    private var pending: ListenableFuture<Void>? = null

    fun attach(camera: Camera) {
        if (this.camera === camera) return
        reset()
        this.camera = camera
        beginGesture()
    }

    fun detach(camera: Camera?) {
        if (this.camera !== camera) return
        reset()
        this.camera = null
    }

    fun beginGesture() {
        // Closing/reopening the camera can restore its default ratio. An in-flight
        // request, however, already owns the latest position during quick re-pinches.
        if (pending == null) requestedRatio = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
    }

    fun scaleBy(factor: Float) {
        if (!factor.isFinite() || factor <= 0f) return
        val activeCamera = camera ?: return
        val state = activeCamera.cameraInfo.zoomState.value ?: return
        val minimum = maxOf(1f, state.minZoomRatio)
        val maximum = minOf(3f, state.maxZoomRatio)
        if (minimum > maximum) return
        val ratio = (requestedRatio * factor).coerceIn(minimum, maximum)
        if (ratio == requestedRatio) return
        requestedRatio = ratio
        val previous = pending
        pending = null
        previous?.cancel(false)
        runCatching {
            val request = activeCamera.cameraControl.setZoomRatio(ratio)
            pending = request
            request.addListener({
                if (pending !== request) return@addListener
                pending = null
                runCatching { request.get() }.onFailure {
                    beginGesture()
                    logFailure(it.cause ?: it)
                }
            }, executor)
        }.onFailure {
            beginGesture()
            logFailure(it)
        }
    }

    /** Drop old callbacks on pause/disposal; CameraX owns the actual camera zoom lifetime. */
    fun reset() {
        val previous = pending
        pending = null
        previous?.cancel(false)
        requestedRatio = 1f
    }

    private fun logFailure(error: Throwable) {
        if (error !is CameraControl.OperationCanceledException && error !is java.util.concurrent.CancellationException) {
            Log.w("Viewfinder", "pinch zoom unavailable", error)
        }
    }
}
