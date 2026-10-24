package com.moge.app.ui.update


import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

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
    val strings = rememberAppStrings()
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
        title = { Text(strings.text(R.string.ui_about_updates_0902e)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(strings.text(R.string.ui_moge_eec60))
                Text(strings.text(R.string.ui_version_1_s_7f8ba, BuildConfig.VERSION_NAME))
                viewModel.mirrorUrl?.let { mirror ->
                    Text(strings.text(R.string.ui_update_mirror_1_s_c2147, mirror), style = MaterialTheme.typography.bodySmall)
                    Text(strings.text(R.string.ui_tries_github_directly_if_the_mirror_fails_6124d), style = MaterialTheme.typography.bodySmall)
                }
                viewModel.projectUrl?.let { url -> TextButton(onClick = { openLink(url) }) { Text(strings.text(R.string.ui_github_project_d0564)) } }
                Text(when (state.phase) {
                    UpdatePhase.UNAVAILABLE -> strings.text(R.string.ui_app_updates_are_not_configured_bfc56)
                    UpdatePhase.IDLE -> strings.text(R.string.ui_check_for_a_new_version_2ecfd)
                    UpdatePhase.CHECKING -> strings.text(R.string.ui_checking_for_updates_c8bea)
                    UpdatePhase.CURRENT -> strings.text(R.string.ui_no_updates_available_9bd2d)
                    UpdatePhase.AVAILABLE -> strings.text(R.string.ui_new_version_1_s_9e0cf, candidate?.manifest?.versionName.orEmpty())
                    UpdatePhase.UNSUPPORTED -> strings.text(R.string.ui_this_update_does_not_support_your_device_5d6b7)
                    UpdatePhase.DOWNLOADING -> strings.text(R.string.ui_downloading_1_s_2fd2f, ((state.downloadedBytes.toDouble() / (candidate?.manifest?.size ?: 1)) * 100).toInt())
                    UpdatePhase.VERIFYING -> strings.text(R.string.ui_verifying_download_804c8)
                    UpdatePhase.READY -> strings.text(R.string.ui_update_downloaded_and_ready_to_install_df95a)
                    UpdatePhase.AWAITING_PERMISSION -> strings.text(R.string.ui_allow_moge_to_install_apps_then_return_to_continue_f6289)
                    UpdatePhase.INSTALLER_OPENED -> strings.text(R.string.ui_confirm_installation_in_the_system_dialog_2431d)
                    UpdatePhase.FAILURE -> strings.text(R.string.ui_update_failed_try_again_879a3)
                })
                if (state.phase == UpdatePhase.DOWNLOADING) {
                    LinearProgressIndicator(progress = { (state.downloadedBytes.toFloat() / (candidate?.manifest?.size ?: 1)).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth())
                } else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                candidate?.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(strings.text(R.string.ui_what_s_new_e86c7), style = MaterialTheme.typography.titleSmall)
                    Text(it)
                }
                candidate?.manifest?.releaseUrl?.let { url ->
                    TextButton(onClick = { openLink(url) }) { Text(strings.text(R.string.ui_open_release_page_e465c)) }
                }
                if (candidate == null && state.phase == UpdatePhase.FAILURE) viewModel.projectUrl?.let { url ->
                    TextButton(onClick = { openLink("$url/releases") }) { Text(strings.text(R.string.ui_open_release_page_e465c)) }
                }
                if (linkFailed) Text(strings.text(R.string.ui_could_not_open_link_try_again_later_fb965))
                if (state.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) {
                    TextButton(onClick = viewModel::cancelDownload) { Text(strings.text(R.string.ui_cancel_download_7115f)) }
                }
                if (state.phase == UpdatePhase.AWAITING_PERMISSION) {
                    TextButton(onClick = viewModel::cancelInstall) { Text(strings.text(R.string.ui_not_now_e6bb5)) }
                }
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.IDLE, UpdatePhase.CURRENT, UpdatePhase.UNSUPPORTED -> TextButton(onClick = viewModel::check) { Text(strings.text(R.string.ui_check_for_updates_a6df3)) }
                UpdatePhase.AVAILABLE -> TextButton(onClick = viewModel::download) { Text(strings.text(R.string.ui_download_update_c1f18)) }
                UpdatePhase.READY -> TextButton(onClick = viewModel::install) { Text(strings.text(R.string.ui_install_update_b93a1)) }
                UpdatePhase.AWAITING_PERMISSION -> TextButton(onClick = viewModel::install) { Text(strings.text(R.string.ui_allow_installation_3769e)) }
                UpdatePhase.FAILURE -> TextButton(onClick = viewModel::retry) { Text(strings.text(R.string.ui_retry_e2d53)) }
                else -> Unit
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.text(R.string.ui_close_6c14b)) } },
    )
}
