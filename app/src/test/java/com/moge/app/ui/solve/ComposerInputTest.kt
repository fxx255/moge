package com.moge.app.ui.solve

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ComposerInputTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun `formula input stays editable and sends exact original without a preview`() {
        val state = mutableStateOf(SolveUiState())
        var sent: String? = null
        compose.setContent { MogeTheme {
            FollowUpBar(state.value, { state.value = state.value.copy(input = it) }, { sent = state.value.input }, {}, {}, {}, {}, {})
        } }
        val raw = """求解 \frac{1}{2}"""
        compose.onNode(hasSetTextAction()).performTextInput(raw)
        compose.onNodeWithTag("composer_math_preview").assertDoesNotExist()
        compose.onNodeWithText("收起公式预览").assertDoesNotExist()
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(raw, sent)
    }
}
