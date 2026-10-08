package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReasoningVisibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun paperShowsReasoningBeforeAnswerText() = verify(Appearance.PAPER)
    @Test fun chalkShowsReasoningBeforeAnswerText() = verify(Appearance.CHALK)

    private fun verify(appearance: Appearance) {
        val item = mutableStateOf(SolveItem.Answer("answer", AnswerState.PREPARING, "",
            reasoning = "先检查条件"))
        compose.setContent { MogeTheme(appearance) {
            AnswerSheet(item.value, {}, {}, false, { _, _ -> })
        } }
        compose.onNodeWithTag("answer-scratch-pad").assertIsDisplayed().performClick()
        compose.onNodeWithText("先检查条件").assertIsDisplayed()
        compose.onNodeWithText("正在读题…").assertDoesNotExist()
        compose.onNodeWithText("正在思考…").assertIsDisplayed()
        compose.runOnIdle { item.value = item.value.copy(state = AnswerState.STREAMING,
            reasoning = "先检查条件\n再计算结果") }
        compose.onNodeWithText("先检查条件\n再计算结果").assertIsDisplayed()
        compose.runOnIdle { item.value = item.value.copy(text = "计算结果") }
        compose.onNode(hasText("草稿 ·", substring = true)).assertIsDisplayed()
        compose.onNodeWithText("先检查条件\n再计算结果").assertIsDisplayed()
        compose.runOnIdle { item.value = item.value.copy(state = AnswerState.COMPLETED, reasoning = "") }
        compose.onNodeWithTag("answer-scratch-pad").assertDoesNotExist()
    }
}
