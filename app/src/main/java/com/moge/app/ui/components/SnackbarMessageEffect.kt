package com.moge.app.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull

/** Acknowledge transient feedback before navigation can cancel its visible lifetime. */
@Composable
internal fun SnackbarMessageEffect(
    message: String?,
    host: SnackbarHostState,
    onConsumed: () -> Unit,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val pending by rememberUpdatedState(message)
    val consume by rememberUpdatedState(onConsumed)
    val label by rememberUpdatedState(actionLabel)
    val action by rememberUpdatedState(onAction)
    LaunchedEffect(host) {
        // Consuming clears the pending message. Filter that null before collectLatest
        // so acknowledgement does not cancel the snackbar currently being displayed.
        snapshotFlow { pending }.filterNotNull().collectLatest { text ->
            val displayedLabel = label
            val displayedAction = action
            consume()
            if (host.showSnackbar(text, actionLabel = displayedLabel) == SnackbarResult.ActionPerformed) {
                displayedAction()
            }
        }
    }
}
