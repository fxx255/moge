package com.moge.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Signed speed near a visible viewport edge; the deletion area never scrolls the list. */
internal fun dragEdgeScrollDirection(pointer: Offset, viewport: Rect, deletion: Rect, edge: Float): Float {
    if (viewport.isEmpty || edge <= 0f || pointer.x !in viewport.left..viewport.right || pointer.y < viewport.top ||
        !deletion.isEmpty && deletion.contains(pointer)) return 0f
    val bottom = if (deletion.isEmpty) viewport.bottom else minOf(viewport.bottom, deletion.top)
    if (pointer.y > bottom || bottom <= viewport.top) return 0f
    val band = minOf(edge, (bottom - viewport.top) / 2f)
    return when {
        pointer.y < viewport.top + band -> -((viewport.top + band - pointer.y) / band).coerceIn(0f, 1f)
        pointer.y > bottom - band -> ((pointer.y - bottom + band) / band).coerceIn(0f, 1f)
        else -> 0f
    }
}
