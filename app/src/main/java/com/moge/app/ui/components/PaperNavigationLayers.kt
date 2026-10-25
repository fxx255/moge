package com.moge.app.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal class PaperControl(val layer: GraphicsLayer, val zIndex: Float,
    val headerInk: Boolean, val alpha: () -> Float, val offsetX: () -> Float) {
    var position by mutableStateOf(Offset.Zero)
}

internal class NavigationPaperHeader(val active: () -> Boolean, val preview: () -> Boolean,
    val targetTilt: Float, val underlay: Boolean, val zIndex: Float) {
    var position by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(Size.Zero)
}

internal class PaperStripPose {
    private var initialized = false
    val tilt = Animatable(-2f)
    val underlayAlpha = Animatable(1f)
    val progress: Float get() = ((tilt.value + 2f) / 4f).coerceIn(0f, 1f)

    suspend fun show(target: Float, underlay: Boolean, motion: Boolean) = coroutineScope {
        val snap = !initialized || !motion
        initialized = true
        launch { if (snap) tilt.snapTo(target) else tilt.animateTo(target, tween(520, easing = FastOutSlowInEasing)) }
        launch {
            val alpha = if (underlay) 1f else 0f
            if (snap) underlayAlpha.snapTo(alpha) else underlayAlpha.animateTo(alpha, tween(180))
        }
    }
}

internal class PaperNavigationScope {
    val controls = mutableStateMapOf<PaperControl, Unit>()
    val headers = mutableStateMapOf<NavigationPaperHeader, Unit>()
    var origin by mutableStateOf(Offset.Zero)
    val pose = PaperStripPose()
    var dragFraction: (() -> Float)? by mutableStateOf(null)
    private var startTilt = -2f
    private var startAlpha = 1f
    var composerHeight by mutableStateOf(0)

    fun beginDrag() {
        startTilt = pose.tilt.value
        startAlpha = pose.underlayAlpha.value
    }

    fun effectivePose(): Pair<Float, Float> {
        val fraction = dragFraction?.invoke() ?: return pose.tilt.value to pose.underlayAlpha.value
        val target = headers.keys.lastOrNull { it.preview() }
            ?: return startTilt to startAlpha
        return (startTilt + (target.targetTilt - startTilt) * fraction) to
            (startAlpha + ((if (target.underlay) 1f else 0f) - startAlpha) * fraction)
    }

    suspend fun finishDrag(commit: Boolean) {
        val header = headers.keys.lastOrNull { if (commit) it.preview() else it.active() }
        pose.tilt.snapTo(header?.targetTilt ?: startTilt)
        pose.underlayAlpha.snapTo(if (header?.underlay != false) 1f else 0f)
    }
}

internal val LocalPaperNavigationScope = staticCompositionLocalOf<PaperNavigationScope?> { null }

@Composable
internal fun navigationPaperHeader(targetTilt: Float, showUnderlay: Boolean, zIndex: Float,
    headerActive: Boolean): NavigationPaperHeader? {
    val scope = LocalPaperNavigationScope.current ?: return null
    val active = rememberUpdatedState(headerActive)
    val preview = rememberUpdatedState(LocalPaperPageMotion.current.swipeTarget)
    val header = remember(scope, zIndex, targetTilt, showUnderlay) {
        NavigationPaperHeader({ active.value }, { preview.value }, targetTilt, showUnderlay, zIndex)
    }
    DisposableEffect(scope, header) {
        scope.headers[header] = Unit
        onDispose { scope.headers.remove(header) }
    }
    val motion = MogeTheme.motionEnabled
    LaunchedEffect(scope, active.value, targetTilt, showUnderlay, motion) {
        if (active.value) scope.pose.show(targetTilt, showUnderlay, motion)
    }
    return header
}

/** Keep one sheet behind navigation and draw fixed controls above both moving pages. */
@Composable
internal fun PaperNavigationLayers(content: @Composable () -> Unit) {
    val scope = remember { PaperNavigationScope() }
    val paper = MogeTheme.paper
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val shadow = Color.Black.copy(alpha = if (paper.isChalk) 0.18f else 0.1f)
    Box(Modifier.fillMaxSize().gridPaper(MaterialTheme.colorScheme.background, paper.grid)
        .onGloballyPositioned { scope.origin = it.positionInRoot() }
        .drawWithContent {
            // Keep a measured source until the destination has been placed. Paper is
            // drawn once, independently of either page's ink or enter/exit animation.
            val activeHeader = scope.headers.keys.lastOrNull { it.active() }
            val header = activeHeader?.takeIf { it.size != Size.Zero }
                ?: if (activeHeader != null) scope.headers.keys.lastOrNull { it.size != Size.Zero } else null
            val (tilt, underlayAlpha) = scope.effectivePose()
            val progress = ((tilt + 2f) / 4f).coerceIn(0f, 1f)
            if (header != null) {
                val offset = header.position - scope.origin
                translate(offset.x, offset.y) {
                    drawPaperUnderlay(header.size, tilt, paper.headerUnderlay, line, underlayAlpha)
                }
            }
            drawContent()
            fun front() {
                if (header == null) return
                val offset = header.position - scope.origin
                translate(offset.x, offset.y - paperHeaderLift(progress)) {
                    drawPaperFront(header.size, progress, paper.headerPaper, line, shadow)
                }
            }
            var frontDrawn = false
            scope.controls.keys.sortedBy { it.zIndex }.forEach { control ->
                if (control.headerInk && header == null) return@forEach
                if (!frontDrawn && control.zIndex >= (header?.zIndex ?: 1f)) {
                    front(); frontDrawn = true
                }
                val offset = control.position - scope.origin
                control.layer.alpha = control.alpha().coerceIn(0f, 1f)
                translate(offset.x + control.offsetX(), offset.y) { drawLayer(control.layer) }
            }
            if (!frontDrawn) front()
        }) {
        CompositionLocalProvider(LocalPaperNavigationScope provides scope) { content() }
    }
}

@Composable
internal fun hasNavigationPaper(): Boolean = LocalPaperNavigationScope.current != null

/** Draw above NavHost entries without changing layout, input ownership or lazy-list measurement. */
@Composable
internal fun Modifier.aboveMovingPages(enabled: Boolean, zIndex: Float, headerInk: Boolean = false,
    offsetX: () -> Float = { 0f }, alpha: () -> Float = { 1f }): Modifier {
    val scope = LocalPaperNavigationScope.current ?: return this
    val layer = rememberGraphicsLayer()
    val currentAlpha = rememberUpdatedState(alpha)
    val currentOffset = rememberUpdatedState(offsetX)
    val control = remember(layer, zIndex, headerInk) {
        PaperControl(layer, zIndex, headerInk, { currentAlpha.value() }, { currentOffset.value() })
    }
    DisposableEffect(scope, control, enabled) {
        if (enabled) scope.controls[control] = Unit
        onDispose { scope.controls.remove(control) }
    }
    return onGloballyPositioned { control.position = it.positionInRoot() }.drawWithContent {
        if (enabled) layer.record { this@drawWithContent.drawContent() }
        else drawContent()
    }
}
