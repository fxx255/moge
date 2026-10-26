package com.moge.app.ui.markdown

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalView

/** Observe the whole reading surface, including table gaps and other native cells. */
@Composable
internal fun Modifier.dismissMarkdownSelectionOnOutsideTap(host: SelectionScrollHost): Modifier {
    val view = LocalView.current
    val origin = remember { SelectionTouchOrigin() }
    return onGloballyPositioned { origin.offset = it.positionInRoot() }.pointerInput(host, view) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val selected = host.selection
            var moved = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pointer = event.changes.firstOrNull { it.id == down.id }
                if (event.changes.size > 1 || pointer == null ||
                    (pointer.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                if (pointer != null && !pointer.pressed && !moved && selected != null && host.selection === selected) {
                    val screen = IntArray(2).also(view::getLocationOnScreen)
                    val point = pointer.position + origin.offset + Offset(screen[0].toFloat(), screen[1].toFloat())
                    if (!selected.containsSelection(point.x, point.y)) {
                        selected.finish()
                        // A dismissing tap must not also open a card or edit another
                        // paragraph. Moves stay untouched so browsing still scrolls.
                        pointer.consume()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
}

private class SelectionTouchOrigin { var offset = Offset.Zero }
