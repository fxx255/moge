package com.moge.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Tape scrolls with the paper; lifted previews keep their layout but shed the tape. */
@Composable
internal fun TapedPaperCard(
    modifier: Modifier = Modifier,
    showTape: Boolean = true,
    content: @Composable (Modifier) -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        content(Modifier.fillMaxWidth().padding(top = 10.dp))
        // Reserved headroom keeps the rotated tape inside the item bounds and
        // above the first text line, even while an animation uses a clipped layer.
        if (showTape) Tape(
            Modifier.align(Alignment.TopCenter).offset(y = 4.dp)
                .testTag("paper-card-tape"),
            width = 48.dp, height = 12.dp, angle = 5f,
        )
    }
}
