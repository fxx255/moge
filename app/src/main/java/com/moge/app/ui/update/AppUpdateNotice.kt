package com.moge.app.ui.update

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.data.update.UpdatePhase

/** Place once at MogeNavHost's root; no install or permission flow runs on launch. */
@Composable
fun AppUpdateNotice() {
    val strings = rememberAppStrings()
    val viewModel: AppUpdateViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val noticeVersion by viewModel.startupNoticeVersion.collectAsStateWithLifecycle()
    var showAbout by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(viewModel) { viewModel.maybeCheckOnLaunch() }
    if (showAbout) AppUpdateDialog(onDismiss = { showAbout = false })
    else if (noticeVersion != null && state.phase == UpdatePhase.AVAILABLE &&
        noticeVersion == state.candidate?.manifest?.versionCode) {
        AlertDialog(
            onDismissRequest = viewModel::dismissStartupNotice,
            title = { Text(strings.text(R.string.ui_update_available_01047)) },
            text = { Text(strings.text(R.string.ui_moge_1_s_is_available_fdc10, state.candidate?.manifest?.versionName.orEmpty())) },
            confirmButton = { TextButton(onClick = {
                viewModel.dismissStartupNotice()
                showAbout = true
            }) { Text(strings.text(R.string.ui_view_update_dfc1a)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissStartupNotice) { Text(strings.text(R.string.ui_later_479fc)) } },
        )
    }
}
