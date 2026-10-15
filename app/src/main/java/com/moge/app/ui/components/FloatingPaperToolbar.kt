package com.moge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.dp

/**
 * Measures the floating controls before the list, so its initial content inset is
 * correct even with large type or extra selection/error actions. The list keeps
 * the full viewport and can scroll behind the opaque controls and their soft edge.
 */
@Composable
internal fun FloatingPaperToolbar(
    modifier: Modifier = Modifier,
    toolbar: @Composable ColumnScope.() -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    val backdrop = MaterialTheme.colorScheme.background
    SubcomposeLayout(modifier.clipToBounds()) { constraints ->
        val header = subcompose(FloatingToolbarSlot.Toolbar) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().background(backdrop)
                    // Empty areas of the opaque toolbar must not activate a card
                    // underneath. Children and the page swipe still handle gestures.
                    .pointerInput(Unit) {
                        awaitPointerEventScope { while (true) awaitPointerEvent() }
                    }, content = toolbar)
                Spacer(Modifier.fillMaxWidth().height(24.dp).background(Brush.verticalGradient(
                    0f to backdrop, 0.35f to backdrop.copy(alpha = 0.85f), 1f to backdrop.copy(alpha = 0f),
                )))
            }
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
