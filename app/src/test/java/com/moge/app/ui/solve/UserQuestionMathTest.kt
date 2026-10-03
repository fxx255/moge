package com.moge.app.ui.solve

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.moge.app.data.prefs.Appearance
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
class UserQuestionMathTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val root: ViewGroup get() = compose.activity.findViewById(android.R.id.content)
    private fun question(raw: String, first: Boolean = true, transcript: String = "") =
        SolveItem.Question("q", raw, emptyList(), transcript, first)

    @Before fun initializeNativeMath() { JLatexMathAndroid.init(compose.activity) }

    @Test fun question_card_uses_native_math_for_all_delimiter_styles() {
        val raw = "求 \$x^2\$，\$\$\\frac{1}{2}\$\$，\\(y_1\\)，\\[\\int_0^1 x dx\\]。"
        val item = question(raw)
        compose.setContent { MogeTheme(Appearance.PAPER) { QuestionCard(item) { _, _ -> } } }
        compose.runOnIdle {
            val view = nativeMathViews(root).single()
            assertEquals(listOf("x^2", "\\frac{1}{2}", "y_1", "\\int_0^1 x dx"), nativeMathSpans(view).map { it.drawable.destination.trim() })
            assertFalse(view.isTextSelectable)
            assertNativeMathDraws(view)
            assertEquals(raw, item.text)
        }
    }

    @Test fun follow_up_note_renders_standalone_math_in_its_sticky_note_color() {
        val raw = "\\frac{1}{2}"
        var expectedColor = 0
        compose.setContent {
            MogeTheme(Appearance.CHALK) {
                expectedColor = MogeTheme.paper.onStickyNote.toArgb()
                FollowUpNote(question(raw, first = false)) { _, _ -> }
            }
        }
        compose.runOnIdle {
            val view = nativeMathViews(root).single()
            assertEquals(expectedColor, view.currentTextColor)
            assertEquals(raw, nativeMathSpans(view).single().drawable.destination.trim())
            assertNativeMathDraws(view)
        }
    }

    @Test fun copying_a_rendered_question_preserves_delimiters_backslashes_and_whitespace() {
        val raw = "  \\(\\frac{1}{2}\\)\n"
        compose.setContent { MogeTheme { QuestionCard(question(raw)) { _, _ -> } } }
        compose.onNodeWithContentDescription("复制题目原文").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(raw, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun copying_a_standalone_follow_up_does_not_include_display_only_wrappers() {
        val raw = " x^2 "
        compose.setContent { MogeTheme { FollowUpNote(question(raw, first = false)) { _, _ -> } } }
        compose.onNodeWithContentDescription("复制题目原文").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(raw, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun ordinary_user_text_keeps_the_plain_text_path() {
        val raw = "价格 $5 到 $10 之间，C:\\temp\\file.txt"
        compose.setContent { MogeTheme { QuestionCard(question(raw)) { _, _ -> } } }
        compose.onNodeWithText(raw).assertExists()
        compose.onNodeWithContentDescription("复制题目原文").assertDoesNotExist()
        compose.runOnIdle { assertTrue(nativeMathViews(root).isEmpty()) }
    }

    @Test fun partial_question_formula_is_displayed_as_its_unmodified_source() {
        val raw = "\\(\\frac{1}{"
        compose.setContent { MogeTheme { QuestionCard(question(raw)) { _, _ -> } } }
        compose.onNodeWithText(raw).assertExists()
        compose.runOnIdle { assertTrue(nativeMathViews(root).isEmpty()) }
        compose.onNodeWithContentDescription("复制题目原文").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(raw, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    @Test fun existing_transcript_math_remains_collapsible_and_native() {
        val raw = "请核对照片"
        compose.setContent { MogeTheme { QuestionCard(question(raw, transcript = "\\(x^2\\)")) { _, _ -> } } }
        compose.runOnIdle { assertTrue(nativeMathViews(root).isEmpty()) }
        compose.onNodeWithText("查看识别文本").performClick()
        compose.runOnIdle {
            val view = nativeMathViews(root).single()
            assertEquals("x^2", nativeMathSpans(view).single().drawable.destination.trim())
        }
        compose.onNodeWithText("收起识别文本").performClick()
        compose.runOnIdle { assertTrue(nativeMathViews(root).isEmpty()) }
    }
}
