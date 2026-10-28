package com.moge.app.ui.solve

import android.os.SystemClock
import androidx.compose.runtime.*
import kotlinx.coroutines.delay

/** Both the header and draft read the attempt's application-owned monotonic origin. */
internal fun answerElapsedMs(startedAtMs: Long?, nowMs: Long, completedMs: Long): Long =
    startedAtMs?.let { (nowMs - it).coerceAtLeast(0) } ?: completedMs.coerceAtLeast(0)

@Composable
internal fun rememberAnswerElapsedMs(startedAtMs: Long?, completedMs: Long): Long {
    var now by remember(startedAtMs) { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtMs) {
        if (startedAtMs != null) while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    return answerElapsedMs(startedAtMs, now, completedMs)
}
