package com.moge.app.ui.capture

import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.MeteringPointFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

internal data class CameraMeteringTarget(val id: Long, val position: Offset)

/** Owns only this preview's tap metering; stale completions cannot affect a later tap or camera. */
internal class CameraTapMetering(private val executor: Executor) {
    private var camera: Camera? = null
    private var pending: ListenableFuture<FocusMeteringResult>? = null
    private var nextId = 0L
    var target by mutableStateOf<CameraMeteringTarget?>(null)
        private set

    fun attach(camera: Camera) {
        if (this.camera === camera) return
        reset()
        this.camera = camera
    }

    fun detach(camera: Camera?) {
        if (this.camera !== camera) return
        reset()
        this.camera = null
    }

    fun focusAt(factory: MeteringPointFactory, position: Offset): Boolean {
        val activeCamera = camera ?: return false
        if (!position.x.isFinite() || !position.y.isFinite()) return false
        // PreviewView's factory accounts for FIT_CENTER, rotation and sensor crop.
        val point = factory.createPoint(position.x, position.y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS)
            .build()
        // CameraX also rejects invalid points from an unready transform or the black bars.
        val supported = runCatching { activeCamera.cameraInfo.isFocusMeteringSupported(action) }
            .onFailure(::logFailure).getOrDefault(false)
        if (!supported) return false
        target = CameraMeteringTarget(++nextId, position)
        runCatching {
            val previous = pending
            pending = null
            previous?.cancel(false)
            val request = activeCamera.cameraControl.startFocusAndMetering(action)
            pending = request
            request.addListener({
                if (pending !== request) return@addListener
                pending = null
                runCatching { request.get() }.onFailure { logFailure(it.cause ?: it) }
            }, executor)
        }.onFailure(::logFailure)
        return true
    }

    fun dismiss(id: Long) {
        if (target?.id == id) target = null
    }

    /** Used on pause, rotation and disposal; hiding the frame alone must not cancel metering. */
    fun reset() {
        target = null
        val previous = pending
        pending = null
        previous?.cancel(false)
        runCatching { camera?.cameraControl?.cancelFocusAndMetering() }.onFailure(::logFailure)
    }

    private fun logFailure(error: Throwable) {
        if (error !is CameraControl.OperationCanceledException && error !is java.util.concurrent.CancellationException) {
            Log.w("Viewfinder", "tap focus/metering unavailable", error)
        }
    }
}
