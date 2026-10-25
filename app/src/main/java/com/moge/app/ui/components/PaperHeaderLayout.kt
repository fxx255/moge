package com.moge.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.Dp

/** Give reading content its final header inset on its very first frame, including large type. */
@Composable
internal fun PaperHeaderLayout(
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    content: @Composable (Dp) -> Unit,
) {
    SubcomposeLayout(modifier) { constraints ->
        val top = subcompose(HeaderSlot.Header) { Box { header() } }.single()
            .measure(constraints.copy(minHeight = 0))
        val body = subcompose(HeaderSlot.Body) { Box { content(top.height.toDp()) } }.single().measure(constraints)
        layout(body.width, body.height) {
            body.placeRelative(0, 0)
            top.placeRelative(0, 0)
        }
    }
}

private enum class HeaderSlot { Header, Body }
