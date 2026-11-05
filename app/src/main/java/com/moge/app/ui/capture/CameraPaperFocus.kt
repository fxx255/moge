package com.moge.app.ui.capture

import android.graphics.Rect
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
    private val onFocus: (Offset, Offset, Int?) -> Unit,
) : AutoCloseable {
    private data class Frame(val luma: ByteArray, val width: Int, val height: Int,
        val crop: Rect, val transform: OutputTransform, val time: Long)
    private val worker = Executors.newSingleThreadExecutor()
    private val tracker = PaperFocusTracker() // main thread only
    @Volatile private var closed = false
    @Volatile private var generation = 0
    private var lastSample = 0L
    private var frame: Frame? = null // main thread only, latest low-resolution Y sample
    val analysis: ImageAnalysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .setResolutionSelector(ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
            .build())
        .build().also { it.setAnalyzer(worker, ::analyze) }

    fun suppress() {
        generation++
        frame = null
        tracker.suppress(SystemClock.elapsedRealtime())
    }

    fun reset() {
        generation++
        frame = null
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
            val source = ImageProxyTransformFactory().getOutputTransform(image)
            val sampled = Frame(luma, width, height, Rect(crop), source, now)
            val point = page?.let { floatArrayOf(crop.left + it.x * crop.width(), crop.top + it.y * crop.height(),
                crop.left + (it.exposure?.x ?: it.x) * crop.width(),
                crop.top + (it.exposure?.y ?: it.y) * crop.height()) }
            mainExecutor.execute {
                if (closed || token != generation) return@execute
                frame = sampled
                if (!canFocus()) return@execute
                tracker.update(page, now) ?: return@execute
                if (point != null) focus(source, point, page?.exposure?.luminance)
            }
        } catch (error: Exception) {
            if (!closed) Log.w("Viewfinder", "paper focus sample unavailable", error)
        } finally { image.close() }
    }

    /** Tap coordinates use the current preview crop/rotation; never sample the screen's black bars. */
    fun luminanceAt(position: Offset): Int? {
        val sampled = frame ?: return null
        if (SystemClock.elapsedRealtime() - sampled.time > 1_200) return null
        val target = preview.outputTransform ?: return null
        return runCatching {
            val point = floatArrayOf(position.x, position.y)
            CoordinateTransform(target, sampled.transform).mapPoints(point)
            samplePaperLuminance(sampled.luma, sampled.width, sampled.height,
                (point[0] - sampled.crop.left) / sampled.crop.width(),
                (point[1] - sampled.crop.top) / sampled.crop.height())
        }.getOrNull()
    }

    private fun focus(source: OutputTransform, point: FloatArray, luminance: Int?) {
        val target = preview.outputTransform ?: return
        runCatching {
            CoordinateTransform(source, target).mapPoints(point)
            val position = Offset(point[0], point[1])
            val exposure = Offset(point[2], point[3])
            if (position.x in 0f..preview.width.toFloat() && position.y in 0f..preview.height.toFloat()) {
                if (exposure.x in 0f..preview.width.toFloat() && exposure.y in 0f..preview.height.toFloat())
                    onFocus(position, exposure, luminance)
                else onFocus(position, position, null)
            }
        }.onFailure { Log.w("Viewfinder", "paper focus transform unavailable", it) }
    }

    override fun close() {
        closed = true
        generation++
        frame = null
        analysis.clearAnalyzer()
        worker.shutdown()
        tracker.reset()
    }
}
