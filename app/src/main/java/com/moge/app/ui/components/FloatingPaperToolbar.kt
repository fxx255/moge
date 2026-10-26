package com.moge.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Measures the floating controls before the list, so its initial content inset is
 * correct even with large type or extra selection/error actions. The list keeps
 * the full viewport and can scroll beneath the raised search sheet and its shadow.
 */
@Composable
internal fun FloatingPaperToolbar(
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    toolbar: @Composable ColumnScope.() -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    // Only the scrolling body is clipped below. The raised toolbar's shadow must
    // be able to extend above this viewport into the paper behind the page title.
    SubcomposeLayout(modifier) { constraints ->
        val toolbarTop = topInset.roundToPx().coerceIn(0, constraints.maxHeight)
        val header = subcompose(FloatingToolbarSlot.Toolbar) {
            Column(Modifier.fillMaxWidth()
                // The toolbar owns its hit region while cards scroll underneath.
                // Children and the page swipe still handle their own gestures.
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                }, content = toolbar)
        }.single().measure(constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight - toolbarTop))
        val body = subcompose(FloatingToolbarSlot.Content) {
            Box(Modifier.drawWithContent {
                // The sheet covers the middle of this band, but its upper rounded
                // cutouts must reveal page paper rather than scrolling card pixels.
                val clipTop = if (toolbarTop == 0) PaperSearchPanelCornerRadius.toPx() else 0f
                clipRect(top = clipTop.coerceAtMost(size.height)) {
                    this@drawWithContent.drawContent()
                }
            }) {
                content(PaddingValues(top = (toolbarTop + header.height).toDp()))
            }
        }.single().measure(constraints)
        layout(body.width, body.height) {
            body.placeRelative(0, 0)
            header.placeRelative(0, toolbarTop)
        }
    }
}

private enum class FloatingToolbarSlot { Toolbar, Content }
