package com.moge.app.ui.solve

import android.app.Application
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.noties.jlatexmath.JLatexMathAndroid

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerMathPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(SolveUiState())
    private var sent: String? = null
    private val root: ViewGroup get() = compose.activity.findViewById(android.R.id.content)
    @Before fun initializeNativeMath() { JLatexMathAndroid.init(compose.activity) }

    private fun render() {
        compose.setContent { MogeTheme {
            FollowUpBar(state.value, { state.value = state.value.copy(input = it) }, { sent = state.value.input },
                {}, {}, {}, {}, {})
        } }
    }
    @Test fun `pasted formula renders inside input panel and sends exact original source`() {
        render()
        val raw = "求解 \\(\\frac{1}{2}\\)".replace("\\\\", "\\")
        compose.onNode(hasSetTextAction()).performTextInput(raw)
        compose.onNodeWithTag(COMPOSER_MATH_PREVIEW_TAG).assertExists()
        compose.runOnIdle {
            val view = nativeMathViews(root).single()
            assertEquals("\\frac{1}{2}", nativeMathSpans(view).single().drawable.destination.trim())
            assertNativeMathDraws(view)
            assertEquals(raw, state.value.input)
        }
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(raw, sent)
    }
    @Test fun `ordinary prose and currency keep only the editable field`() {
        state.value = state.value.copy(input = "这本书价格是 $20，请问怎么计算")
        render()
        compose.onNodeWithTag(COMPOSER_MATH_PREVIEW_TAG).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertTextContains(state.value.input)
    }
    @Test fun `unfinished math is editable without attempting native formula layout`() {
        state.value = state.value.copy(input = "\\frac{1}{")
        render()
        compose.onNodeWithTag(COMPOSER_MATH_PREVIEW_TAG).assertExists()
        compose.onNodeWithText("公式尚未完成，继续编辑原文").assertExists()
        compose.runOnIdle { assertTrue(nativeMathViews(root).isEmpty()) }
        compose.onNode(hasSetTextAction()).performTextInputSelection(TextRange(state.value.input.length))
        compose.onNode(hasSetTextAction()).performTextInput("2}")
        assertEquals("\\frac{1}{2}", state.value.input)
        compose.runOnIdle { assertEquals("\\frac{1}{2}", nativeMathSpans(nativeMathViews(root).single()).single().drawable.destination.trim()) }
    }
    @Test fun `collapsing and reopening preview preserves editable source`() {
        val raw = "\\frac{1}{2}"
        state.value = state.value.copy(input = raw)
        render()
        compose.onNodeWithText("收起公式预览").performClick()
        compose.onNodeWithTag(COMPOSER_MATH_PREVIEW_TAG).assertDoesNotExist()
        assertEquals(raw, state.value.input)
        compose.onNodeWithText("展开公式预览").performClick()
        compose.onNodeWithTag(COMPOSER_MATH_PREVIEW_TAG).assertExists()
        assertEquals(raw, state.value.input)
    }
    @Test fun `long preview height is bounded while send keeps all pasted characters`() {
        val raw = "\\[x^2\\] " + "附加条件 ".repeat(1400)
        state.value = state.value.copy(input = raw)
        render()
        val height = compose.onNodeWithTag(COMPOSER_MATH_PREVIEW_TAG).fetchSemanticsNode().boundsInRoot.height
        assertTrue(height <= COMPOSER_PREVIEW_MAX_HEIGHT_DP * compose.density.density + 1f)
        compose.onNodeWithText("仅预览前 $COMPOSER_PREVIEW_CHAR_LIMIT 个字符，发送保留全部原文").assertExists()
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(raw, sent)
    }
}
