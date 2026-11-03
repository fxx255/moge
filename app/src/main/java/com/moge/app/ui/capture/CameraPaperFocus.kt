package com.moge.app.ui.capture

import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.compose.ui.geometry.Offset
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Samples only the Y plane off the UI thread. No photo copies, OCR, or network requests. */
@androidx.annotation.OptIn(markerClass = [androidx.camera.view.TransformExperimental::class])
internal class CameraPaperFocus(
    private val preview: PreviewView,
    private val mainExecutor: Executor,
    private val canFocus: () -> Boolean,
    private val onFocus: (Offset) -> Unit,
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor()
    private val tracker = PaperFocusTracker() // main thread only
    @Volatile private var closed = false
    @Volatile private var generation = 0
    private var lastSample = 0L
    val analysis: ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build())
        .build().also { it.setAnalyzer(worker, ::analyze) }

    fun suppress() {
        generation++
        tracker.suppress(SystemClock.elapsedRealtime())
    }

    fun reset() {
        generation++
        tracker.reset()
    }

    internal fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (closed || now - lastSample < 400) return
            lastSample = now
            val token = generation
            val crop = image.cropRect
            val width = 64
            val height = (width * crop.height() / crop.width().coerceAtLeast(1)).coerceIn(24, 96)
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer.duplicate()
            val base = buffer.position()
            val luma = ByteArray(width * height)
            for (y in 0 until height) for (x in 0 until width) {
                val px = crop.left + ((x + .5f) * crop.width() / width).toInt()
                val py = crop.top + ((y + .5f) * crop.height() / height).toInt()
                luma[y * width + x] = buffer.get(base + py * plane.rowStride + px * plane.pixelStride)
            }
            val page = detectPaperFocus(luma, width, height)
            // Default transform consumes unrotated buffer coordinates, matching the Y-plane sample.
            val source = if (page != null) ImageProxyTransformFactory().getOutputTransform(image) else null
            val point = page?.let { floatArrayOf(crop.left + it.x * crop.width(), crop.top + it.y * crop.height()) }
            mainExecutor.execute {
                if (closed || token != generation || !canFocus()) return@execute
                tracker.update(page, now) ?: return@execute
                if (source != null && point != null) focus(source, point)
            }
        } catch (error: Exception) {
            if (!closed) Log.w("Viewfinder", "paper focus sample unavailable", error)
        } finally { image.close() }
    }

    private fun focus(source: OutputTransform, point: FloatArray) {
        val target = preview.outputTransform ?: return
        runCatching {
            CoordinateTransform(source, target).mapPoints(point)
            val position = Offset(point[0], point[1])
            if (position.x in 0f..preview.width.toFloat() && position.y in 0f..preview.height.toFloat()) onFocus(position)
        }.onFailure { Log.w("Viewfinder", "paper focus transform unavailable", it) }
    }

    override fun close() {
        closed = true
        generation++
        analysis.clearAnalyzer()
        worker.shutdown()
        tracker.reset()
    }
}
