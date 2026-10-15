package com.moge.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource

/** Observe consumed list motion without consuming gestures or moving the floating controls. */
internal class PaperScrollState : NestedScrollConnection {
    var offsetY by mutableFloatStateOf(0f)
        private set

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        offsetY += consumed.y
        return Offset.Zero
    }
}

@Composable
internal fun rememberPaperScrollState() = remember { PaperScrollState() }
