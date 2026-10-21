package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.*
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SolveAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    @Test fun `blackboard completed answer regenerates the original request`() {
        var regenerated: String? = null
        compose.setContent {
            MogeTheme(Appearance.CHALK) {
                AnswerSheet(SolveItem.Answer("a", AnswerState.COMPLETED, "答案为 1", regenerateRequestId = "r"),
                    {}, { regenerated = it }, true, { _, _ -> })
            }
        }
        compose.onNodeWithContentDescription("重新生成").performClick()
        assertEquals("r", regenerated)
    }
    @Test fun `failed answer offers explicit resend action`() {
        var retried: String? = null
        compose.setContent {
            MogeTheme {
                AnswerSheet(SolveItem.Answer("a", AnswerState.FAILED, "", failureMessage = "网络断开", retryRequestId = "r"),
                    { retried = it }, {}, true, { _, _ -> })
            }
        }
        compose.onNodeWithText("网络断开").assertExists()
        compose.onNodeWithText("重新发送").performClick()
        assertEquals("r", retried)
    }

    @Test fun `answer first unfolds stored explanation and shares without expanding it`() {
        var shared = 0
        var saved = 0
        compose.setContent {
            MogeTheme {
                AnswerSheet(SolveItem.Answer("a", AnswerState.COMPLETED, "完整推导过程", finalAnswer = "答案为 2"),
                    {}, {}, true, { _, _ -> }, answerFirst = true, onShare = { shared++ }, onSave = { saved++ })
            }
        }
        compose.onNodeWithText("展开解答").assertExists()
        compose.onNodeWithText("完整推导过程").assertDoesNotExist()
        compose.onNodeWithContentDescription("分享对话图片").performClick()
        compose.onNodeWithContentDescription("收藏到题册").performClick()
        assertEquals(1, shared)
        assertEquals(1, saved)
        compose.onNodeWithText("展开解答").performClick()
        compose.onNodeWithText("收起解答").assertExists()
        compose.onNodeWithText("收起解答").performClick()
        compose.onNodeWithText("展开解答").assertExists()
    }
}
