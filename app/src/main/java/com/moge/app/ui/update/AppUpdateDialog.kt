package com.moge.app.ui.update

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.BuildConfig
import com.moge.app.data.update.UpdatePhase

/** Settings integration: invoke AppUpdateDialog(onDismiss = { showAbout = false }). */
@Composable
fun AppUpdateDialog(onDismiss: () -> Unit) {
    val viewModel: AppUpdateViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var linkFailed by remember { mutableStateOf(false) }
    var launchedAction by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (launchedAction == Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES || state.phase == UpdatePhase.AWAITING_PERMISSION) viewModel.onResume()
        else viewModel.installerReturned()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    LaunchedEffect(state.phase) {
        if (state.phase == UpdatePhase.AWAITING_PERMISSION) viewModel.onResume()
    }
    LaunchedEffect(viewModel) {
        viewModel.intents.collect { intent: Intent ->
            launchedAction = intent.action
            try { launcher.launch(intent) } catch (_: Exception) { viewModel.launchFailed() }
        }
    }
    fun openLink(url: String) {
        linkFailed = runCatching { uriHandler.openUri(url) }.isFailure
    }
    val busy = state.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)
    val candidate = state.candidate
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于与更新") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("墨格 Moge")
                Text("当前版本 ${BuildConfig.VERSION_NAME}")
                viewModel.projectUrl?.let { url -> TextButton(onClick = { openLink(url) }) { Text("GitHub 项目") } }
                Text(when (state.phase) {
                    UpdatePhase.UNAVAILABLE -> "暂未配置应用更新。"
                    UpdatePhase.IDLE -> "检查是否有新版本。"
                    UpdatePhase.CHECKING -> "正在检查更新…"
                    UpdatePhase.CURRENT -> "目前没有可用的新版本。"
                    UpdatePhase.AVAILABLE -> "发现新版本 ${candidate?.manifest?.versionName.orEmpty()}"
                    UpdatePhase.UNSUPPORTED -> "新版本暂不支持此设备。"
                    UpdatePhase.DOWNLOADING -> "正在下载 ${((state.downloadedBytes.toDouble() / (candidate?.manifest?.size ?: 1)) * 100).toInt()}%"
                    UpdatePhase.VERIFYING -> "正在检查安装文件…"
                    UpdatePhase.READY -> "新版本已下载，可以安装。"
                    UpdatePhase.AWAITING_PERMISSION -> "请允许墨格安装应用，返回后继续安装。"
                    UpdatePhase.INSTALLER_OPENED -> "请在系统界面确认安装。"
                    UpdatePhase.FAILURE -> "更新未能完成，请重试。"
                })
                if (state.phase == UpdatePhase.DOWNLOADING) {
                    LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / (candidate?.manifest?.size ?: 1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                } else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                candidate?.notes?.takeIf { it.isNotBlank() }?.let {
                    Text("更新内容", style = MaterialTheme.typography.titleSmall)
                    Text(it)
                }
                candidate?.manifest?.releaseUrl?.let { url ->
                    TextButton(onClick = { openLink(url) }) { Text("打开发布页") }
                }
                if (candidate == null && state.phase == UpdatePhase.FAILURE) viewModel.projectUrl?.let { url ->
                    TextButton(onClick = { openLink("$url/releases") }) { Text("打开发布页") }
                }
                if (linkFailed) Text("暂时无法打开链接，请稍后重试。")
                if (state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) {
                    TextButton(onClick = viewModel::cancelDownload) { Text("取消下载") }
                }
                if (state.phase == UpdatePhase.AWAITING_PERMISSION) {
                    TextButton(onClick = viewModel::cancelInstall) { Text("暂不安装") }
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.IDLE, UpdatePhase.CURRENT, UpdatePhase.UNSUPPORTED -> TextButton(onClick = viewModel::check) { Text("检查更新") }
                UpdatePhase.AVAILABLE -> TextButton(onClick = viewModel::download) { Text("下载更新") }
                UpdatePhase.READY -> TextButton(onClick = viewModel::install) { Text("安装更新") }
                UpdatePhase.AWAITING_PERMISSION -> TextButton(onClick = viewModel::install) { Text("允许安装") }
                UpdatePhase.FAILURE -> TextButton(onClick = viewModel::retry) { Text("重试") }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
