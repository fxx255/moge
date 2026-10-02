package com.moge.app.ui.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.data.update.AppUpdateRepository
import com.moge.app.data.update.UpdateRetry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppUpdateViewModel @Inject constructor(private val updates: AppUpdateRepository) : ViewModel() {
    val state = updates.state
    val projectUrl = updates.projectUrl
    val startupNoticeVersion = updates.startupNoticeVersion
    private val installIntents = Channel<Intent>(Channel.BUFFERED)
    val intents = installIntents.receiveAsFlow()
    private var preparingInstall = false

    fun check() = updates.check()
    fun maybeCheckOnLaunch() = updates.checkOnStartup()
    fun dismissStartupNotice() = updates.dismissStartupNotice()
    fun download() = updates.download()
    fun cancelDownload() = updates.cancelDownload()
    fun cancelInstall() = updates.cancelInstall()
    fun launchFailed() = updates.launchFailed()
    fun installerReturned() = updates.installerReturned()
    fun retry() = when (state.value.retry) {
        UpdateRetry.CHECK -> check()
        UpdateRetry.DOWNLOAD -> download()
        UpdateRetry.INSTALL -> install()
    }

    fun install() = prepare { updates.prepareInstall() }
    fun onResume() = prepare { updates.resumePendingInstall() }

    private fun prepare(block: suspend () -> Intent?) {
        if (preparingInstall) return
        preparingInstall = true
        viewModelScope.launch {
            try {
                block()?.let { installIntents.send(it) }
            } finally {
                preparingInstall = false
            }
        }
    }
}
