package com.moge.app.ui.update

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
            title = { Text("发现新版本") },
            text = { Text("墨格 ${state.candidate?.manifest?.versionName.orEmpty()} 已可更新。") },
            confirmButton = { TextButton(onClick = {
                viewModel.dismissStartupNotice()
                showAbout = true
            }) { Text("查看更新") } },
            dismissButton = { TextButton(onClick = viewModel::dismissStartupNotice) { Text("稍后") } },
        )
    }
}
