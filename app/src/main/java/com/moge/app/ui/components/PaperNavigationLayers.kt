package com.moge.app.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot

private class PaperControl(val layer: GraphicsLayer, val zIndex: Float) {
    var position by mutableStateOf(Offset.Zero)
}

private class PaperNavigationScope {
    val controls = mutableStateMapOf<PaperControl, Unit>()
    var origin by mutableStateOf(Offset.Zero)
}

private val LocalPaperNavigationScope = staticCompositionLocalOf<PaperNavigationScope?> { null }

/** Keep one sheet behind navigation and draw fixed controls above both moving pages. */
@Composable
internal fun PaperNavigationLayers(content: @Composable () -> Unit) {
    val scope = remember { PaperNavigationScope() }
    GridPaper(Modifier.fillMaxSize().onGloballyPositioned { scope.origin = it.positionInRoot() }
        .drawWithContent {
            drawContent()
            scope.controls.keys.sortedBy { it.zIndex }.forEach { control ->
                val offset = control.position - scope.origin
                translate(offset.x, offset.y) { drawLayer(control.layer) }
            }
        }) {
        CompositionLocalProvider(LocalPaperNavigationScope provides scope) { content() }
    }
}

@Composable
internal fun hasNavigationPaper(): Boolean = LocalPaperNavigationScope.current != null

/** Draw above NavHost entries without changing layout, input ownership or lazy-list measurement. */
@Composable
internal fun Modifier.aboveMovingPages(enabled: Boolean, zIndex: Float): Modifier {
    val scope = LocalPaperNavigationScope.current ?: return this
    val layer = rememberGraphicsLayer()
    val control = remember(layer, zIndex) { PaperControl(layer, zIndex) }
    DisposableEffect(scope, control, enabled) {
        if (enabled) scope.controls[control] = Unit
        onDispose { scope.controls.remove(control) }
    }
    return onGloballyPositioned { control.position = it.positionInRoot() }.drawWithContent {
        if (enabled) layer.record { this@drawWithContent.drawContent() }
        else drawContent()
    }
}
