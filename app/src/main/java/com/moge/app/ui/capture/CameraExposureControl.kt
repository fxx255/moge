package com.moge.app.ui.capture

import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import kotlin.math.roundToInt

/** Maps a continuous slider linearly onto this camera's supported compensation steps. */
internal class CameraExposureControl(private val executor: Executor) {
    private var camera: Camera? = null
    private var pending: ListenableFuture<Int>? = null
    private var requestedIndex: Int? = null
    private var manuallyAdjusted = false
    var supported by mutableStateOf(false)
        private set
    var fraction by mutableFloatStateOf(.5f)
        private set

    fun attach(camera: Camera) {
        if (this.camera === camera) return
        reset()
        this.camera = camera
        manuallyAdjusted = false
        refresh()
    }

    fun detach(camera: Camera?) {
        if (this.camera !== camera) return
        reset()
        this.camera = null
        supported = false
    }

    fun refresh() {
        val state = camera?.cameraInfo?.exposureState ?: return
        val range = state.exposureCompensationRange
        supported = state.isExposureCompensationSupported && range.upper > range.lower
        if (pending == null) {
            requestedIndex = state.exposureCompensationIndex
            fraction = if (supported) (state.exposureCompensationIndex.toFloat() - range.lower) /
                (range.upper.toFloat() - range.lower) else .5f
        }
    }

    fun adjustTo(value: Float) {
        if (!supported || !value.isFinite()) return
        manuallyAdjusted = true
        requestFraction(value)
    }

    /** A small absolute AE bias; repeated focus taps never add brightness or erase a manual choice. */
    fun applyMeteringDefault() {
        if (!supported || manuallyAdjusted) return
        val state = camera?.cameraInfo?.exposureState ?: return
        val step = state.exposureCompensationStep.toFloat()
        if (!step.isFinite() || step <= 0f) return
        val range = state.exposureCompensationRange
        val index = (1f / 3f / step).roundToInt().coerceIn(range.lower, range.upper)
        if (index <= 0) return
        requestFraction((index.toFloat() - range.lower) / (range.upper.toFloat() - range.lower))
    }

    private fun requestFraction(value: Float) {
        val active = camera ?: return
        val range = active.cameraInfo.exposureState.exposureCompensationRange
        val next = (range.lower + value.coerceIn(0f, 1f) * (range.upper.toFloat() - range.lower))
            .roundToInt().coerceIn(range.lower, range.upper)
        fraction = value.coerceIn(0f, 1f)
        if (next == requestedIndex) return
        requestedIndex = next
        val previous = pending
        pending = null
        previous?.cancel(false)
        runCatching {
            val request = active.cameraControl.setExposureCompensationIndex(next)
            pending = request
            request.addListener({
                if (pending !== request || camera !== active) return@addListener
                pending = null
                runCatching { request.get() }.onFailure {
                    refresh()
                    logFailure(it.cause ?: it)
                }
            }, executor)
        }.onFailure { refresh(); logFailure(it) }
    }

    fun reset() {
        val previous = pending
        pending = null
        requestedIndex = null
        previous?.cancel(false)
    }

    private fun logFailure(error: Throwable) {
        if (error !is CameraControl.OperationCanceledException && error !is java.util.concurrent.CancellationException)
            Log.w("Viewfinder", "exposure compensation unavailable", error)
    }
}
