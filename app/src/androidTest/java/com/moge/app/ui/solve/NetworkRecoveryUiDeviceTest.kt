package com.moge.app.ui.solve

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moge.app.data.llm.NetworkRecoveryStatus
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.settings.NetworkResumeSetting
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkRecoveryUiDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun recoveryStatusRetainsVisibleAnswerAndOptionHasAnAccessibleToggle() {
        val theme = mutableStateOf(Appearance.PAPER)
        val enabled = mutableStateOf(false)
        val status = mutableStateOf(NetworkRecoveryStatus.WAITING)
        val origin = SystemClock.elapsedRealtime() - 15_000
        compose.setContent { MogeTheme(theme.value) {
            Column(Modifier.width(360.dp)) {
                NetworkResumeSetting(enabled.value) { enabled.value = it }
                AnswerSheet(SolveItem.Answer("recovering", AnswerState.STREAMING, "已经生成的内容保留在这里。",
                    startedAtMs = origin, networkStatus = status.value), {}, {}, true, { _, _ -> })
            }
        } }
        for (appearance in listOf(Appearance.PAPER, Appearance.CHALK)) {
            compose.runOnIdle { theme.value = appearance }
            for ((state, text) in listOf(NetworkRecoveryStatus.WAITING to "等待网络恢复…",
                NetworkRecoveryStatus.RETRYING to "正在重新连接…", NetworkRecoveryStatus.CONTINUING to "网络中断，正在准备续写…")) {
                compose.runOnIdle { status.value = state }
                compose.onNodeWithText(text).assertIsDisplayed()
                compose.onNodeWithTag("answer-header").assertIsDisplayed()
            }
        }
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { assertTrue(enabled.value) }
    }
}
