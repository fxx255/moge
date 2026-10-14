package com.moge.app.ui.markdown

import android.graphics.RectF
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.min

internal val LocalSelectionScrollHost = staticCompositionLocalOf<SelectionScrollHost?> { null }

/** Screen coordinates exclude the title, composer, keyboard and system bars. */
internal class SelectionScrollHost(
    private val scope: CoroutineScope,
    val density: Float,
    private val scroll: suspend (MutatePriority, suspend ScrollScope.() -> Unit) -> Unit,
) {
    var viewport = RectF()
    private var owner: MarkdownSelectionController? = null
    private var job: Job? = null

    fun claim(controller: MarkdownSelectionController) {
        if (owner !== controller) owner?.finish()
        owner = controller
    }

    fun release(controller: MarkdownSelectionController) {
        if (owner !== controller) return
        job?.cancel()
        job = null
        owner = null
    }

    fun stopScrolling() {
        job?.cancel()
        job = null
    }

    fun dispose() { owner?.finish(); stopScrolling() }

    fun drag(controller: MarkdownSelectionController) {
        if (owner !== controller || job?.isActive == true) return
        job = scope.launch {
            scroll(MutatePriority.UserInput) {
                var previousFrame = withFrameNanos { it }
                var moved = false
                while (controller.dragging && owner === controller) {
                    val frame = withFrameNanos { it }
                    if (moved) controller.extendAtPointer()
                    // Frame time, rather than a fixed step, also supports 90/120 Hz displays.
                    val seconds = ((frame - previousFrame) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    previousFrame = frame
                    val speed = selectionEdgeVelocity(controller.pointerY, viewport, density)
                    // Re-evaluate after the next lazy layout, including a stationary finger.
                    moved = speed != 0f && controller.canExtend(speed) && scrollBy(speed * seconds) != 0f
                }
            }
        }
    }
}

/** Positive scroll reveals later lines; speed grows smoothly toward either edge. */
internal fun selectionEdgeVelocity(y: Float, viewport: RectF, density: Float): Float {
    if (!y.isFinite() || viewport.height() <= 0 || density <= 0) return 0f
    val band = min(64f * density, viewport.height() / 3f)
    val top = ((viewport.top + band - y) / band).coerceIn(0f, 1f)
    val bottom = ((y - viewport.bottom + band) / band).coerceIn(0f, 1f)
    return (bottom * bottom - top * top) * 900f * density
}
