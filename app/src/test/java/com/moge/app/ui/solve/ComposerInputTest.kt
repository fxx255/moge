package com.moge.app.ui.solve

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextRange
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

    @Test fun `editing waits for draft loading then focuses and shows keyboard once`() {
        val state = mutableStateOf(SolveUiState(input = "普通草稿"))
        var keyboardShows = 0
        val keyboard = object : SoftwareKeyboardController {
            override fun show() { keyboardShows++ }
            override fun hide() = Unit
        }
        compose.setContent { MogeTheme {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                FollowUpBar(state.value, { state.value = state.value.copy(input = it) }, {}, {}, {}, {}, {}, {})
            }
        } }
        val input = compose.onNodeWithTag("conversation-input")
        input.assertIsNotFocused()
        compose.runOnIdle {
            state.value = state.value.copy(editingQuestionId = "old", switchingBranch = true)
        }
        input.assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, keyboardShows) }
        compose.runOnIdle {
            state.value = state.value.copy(input = "恢复的旧输入", switchingBranch = false)
        }
        input.assertIsFocused().assertTextEquals("恢复的旧输入")
        compose.runOnIdle { assertEquals(1, keyboardShows) }
        input.performTextInputSelection(TextRange(2))
        input.performTextInput("改")
        input.assertTextEquals("恢复改的旧输入")
        compose.runOnIdle { assertEquals(1, keyboardShows) }
        compose.runOnIdle { state.value = state.value.copy(editingQuestionId = null) }
        compose.runOnIdle { state.value = state.value.copy(editingQuestionId = "another", input = "第二次修改") }
        input.assertIsFocused().assertTextEquals("第二次修改")
        compose.runOnIdle { assertEquals(2, keyboardShows) }
    }
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

    @Test fun `native image paste adds an attachment without changing text or selection`() {
        val context = compose.activity
        val image = ClipboardImageFixture(context)
        val state = mutableStateOf(SolveUiState(input = "abcd"))
        var pasted = emptyList<android.net.Uri>()
        compose.setContent { MogeTheme {
            FollowUpBar(state.value, { state.value = state.value.copy(input = it) }, {}, {}, {}, {}, {}, {},
                onPasteImages = { pasted = it })
        } }
        val input = compose.onNode(hasSetTextAction())
        input.performClick().performTextInputSelection(TextRange(1, 3))
        compose.runOnIdle {
            clipboard().setPrimaryClip(ClipData.newUri(context.contentResolver, "复制的图片", image.uri))
        }
        input.performSemanticsAction(SemanticsActions.PasteText) { it() }
        compose.runOnIdle {
            assertEquals(listOf(image.uri), pasted)
            assertEquals("abcd", state.value.input)
            clipboard().setPrimaryClip(ClipData.newPlainText("文字", "替换"))
        }
        input.performSemanticsAction(SemanticsActions.PasteText) { it() }
        input.assertTextEquals("a替换d")
        compose.runOnIdle { assertEquals("a替换d", state.value.input) }
    }

    @Test fun `mixed image and text paste retains text at cursor and external clear resets field`() {
        val context = compose.activity
        val image = ClipboardImageFixture(context)
        val state = mutableStateOf(SolveUiState(input = "前后"))
        var pasted = emptyList<android.net.Uri>()
        compose.setContent { MogeTheme {
            FollowUpBar(state.value, { state.value = state.value.copy(input = it) },
                { state.value = state.value.copy(input = "") }, {}, {}, {}, {}, {}, onPasteImages = { pasted = it })
        } }
        val input = compose.onNode(hasSetTextAction())
        input.performClick().performTextInputSelection(TextRange(1))
        compose.runOnIdle {
            clipboard().setPrimaryClip(ClipData("图文", arrayOf("image/png", "text/plain"), ClipData.Item(image.uri)).apply {
                addItem(ClipData.Item("注释"))
            })
        }
        input.performSemanticsAction(SemanticsActions.PasteText) { it() }
        input.assertTextEquals("前注释后")
        compose.runOnIdle { assertEquals(listOf(image.uri), pasted) }
        compose.onNodeWithContentDescription("发送").performClick()
        input.assertTextEquals("", "输入问题")
        compose.runOnIdle { state.value = state.value.copy(input = "恢复的草稿") }
        input.assertTextEquals("恢复的草稿")
    }

    private fun clipboard() = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
}
