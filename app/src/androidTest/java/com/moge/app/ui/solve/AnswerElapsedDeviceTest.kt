package com.moge.app.ui.solve

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnswerElapsedDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun headerAndDraftContinueAfterProseAndReentryThenFreezeAtSavedDuration() {
        val visible = mutableStateOf(true)
        val item = mutableStateOf(SolveItem.Answer("clock", AnswerState.STREAMING, "",
            modelLabel = "计时测试", startedAtMs = SystemClock.elapsedRealtime() - 15_000,
            reasoning = "正在计算同一道题"))
        compose.setContent { MogeTheme(Appearance.PAPER) {
            if (visible.value) AnswerSheet(item.value, {}, {}, true, { _, _ -> })
        } }
        fun header() = compose.onNodeWithTag("answer-header").fetchSemanticsNode()
            .config[SemanticsProperties.Text].joinToString("") { it.text }
        fun seconds() = Regex("(\\d+)s$").find(header())!!.groupValues[1].toInt()
        val before = seconds()
        assertTrue(before >= 15)
        compose.runOnIdle { item.value = item.value.copy(text = "正文已经开始，仍在生成。") }
        compose.waitUntil(5000) { seconds() > before }
        val afterProse = seconds()
        compose.onNodeWithText("草稿 · ${afterProse}s").assertIsDisplayed()

        compose.runOnIdle { visible.value = false }
        SystemClock.sleep(1100)
        compose.runOnIdle { visible.value = true }
        assertTrue("Reentering must retain the task origin", seconds() > afterProse)

        compose.runOnIdle { item.value = item.value.copy(state = AnswerState.COMPLETED, durationMs = 42_000, startedAtMs = null) }
        compose.onNodeWithTag("answer-scratch-pad").assertDoesNotExist()
        assertEquals("解答 · 计时测试 · 42s", header())
        SystemClock.sleep(1100)
        assertEquals("解答 · 计时测试 · 42s", header())
    }
}
