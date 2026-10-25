package com.moge.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/** Participate in hit testing across the header, including otherwise empty title space.
 * Children receive events first, so their buttons and mode menu retain normal behavior.
 * This has no accessibility action: empty header space is not an extra button.
 */
internal fun Modifier.headerInputShield(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
        // A title long-press or button already owns this gesture. Consuming its
        // later events here cancels the child's press detector on the Final pass.
        if (down.isConsumed) return@awaitEachGesture
        down.consume()
        do {
            val event = awaitPointerEvent(PointerEventPass.Main)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}
