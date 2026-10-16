package com.moge.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout

/**
 * Measures the floating controls before the list, so its initial content inset is
 * correct even with large type or extra selection/error actions. The list keeps
 * the full viewport and can scroll beneath the raised search sheet and its shadow.
 */
@Composable
internal fun FloatingPaperToolbar(
    modifier: Modifier = Modifier,
    toolbar: @Composable ColumnScope.() -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    SubcomposeLayout(modifier.clipToBounds()) { constraints ->
        val header = subcompose(FloatingToolbarSlot.Toolbar) {
            Column(Modifier.fillMaxWidth()
                // The toolbar owns its hit region while cards scroll underneath.
                // Children and the page swipe still handle their own gestures.
                .pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent() }
                }, content = toolbar)
        }.single().measure(constraints.copy(minHeight = 0))
        val body = subcompose(FloatingToolbarSlot.Content) {
            content(PaddingValues(top = header.height.toDp()))
        }.single().measure(constraints)
        layout(body.width, body.height) {
            body.placeRelative(0, 0)
            header.placeRelative(0, 0)
        }
    }
}

private enum class FloatingToolbarSlot { Toolbar, Content }
