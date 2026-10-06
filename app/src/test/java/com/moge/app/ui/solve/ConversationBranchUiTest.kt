package com.moge.app.ui.solve

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationBranchUiTest {
    @get:Rule val compose = createComposeRule()
    private fun question(id: String, index: Int = 0) = SolveItem.Question(id,
        "请把生成多项式改为 g₁(D)=1+D+D²，并解释各抽头的作用。", emptyList(), "", false,
        versionIds = listOf("q-old", "q-new"), versionIndex = index)

    @Test fun questionTapEditsAndVersionArrowsRespectBounds() {
        val item = mutableStateOf(question("q-old"))
        var edited: String? = null
        var switched: String? = null
        compose.setContent { MogeTheme {
            FollowUpNote(item.value, onEdit = { edited = it }, onSwitchVersion = { switched = it }, onOpenImages = { _, _ -> })
        } }
        compose.onNodeWithText(item.value.text).performClick()
        assertEquals("q-old", edited)
        compose.onNodeWithContentDescription("上一个输入版本").assertIsNotEnabled()
        compose.onNodeWithContentDescription("下一个输入版本").performClick()
        assertEquals("q-new", switched)
        compose.runOnIdle { item.value = question("q-new", 1) }
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("下一个输入版本").assertIsNotEnabled()
        compose.onNodeWithContentDescription("上一个输入版本").performClick()
        assertEquals("q-old", switched)
    }

    @Test fun versionSwitchKeepsForkInViewAndRestoresPreviousBranchViewport() {
        val questions = (0..30).map { index ->
            SolveItem.Question("q$index", "第 $index 个问题：" + "用于保留分支阅读位置。".repeat(12),
                emptyList(), "", false, versionIds = if (index == 12) listOf("q12", "q12b") else listOf("q$index"))
        }
        val items = mutableStateOf<List<SolveItem>>(questions)
        val positions = mutableMapOf<String, ConversationViewport>()
        var saved: ConversationViewport? = null
        compose.setContent { MogeTheme {
            SolveList(SolveUiState(conversationId = "c", items = items.value), {}, {}, { _, _ -> }, {}, {},
                saveViewport = { saved = it }, readBranchViewport = positions::get,
                saveBranchViewport = { id, viewport -> positions[id] = viewport },
                onSwitchVersion = { target ->
                    items.value = if (target == "q12b") questions.mapIndexed { index, q ->
                        if (index < 12) q else q.copy(id = q.id + "b", versionIndex = if (index == 12) 1 else 0)
                    } else questions
                })
        } }
        compose.onNodeWithTag("conversation-list").performScrollToIndex(12)
        compose.waitForIdle()
        val before = requireNotNull(saved)
        compose.onNodeWithContentDescription("下一个输入版本").performClick()
        compose.waitForIdle()
        assertEquals("q12b", requireNotNull(saved).messageId)
        assertEquals(before.offset, saved!!.offset)
        compose.onNodeWithContentDescription("上一个输入版本").performClick()
        compose.waitForIdle()
        assertEquals(before, saved)
    }

    @Test fun paperEditingPreview() = editingPreview(Appearance.PAPER, "paper")
    @Test fun chalkEditingPreview() = editingPreview(Appearance.CHALK, "chalk")
    private fun editingPreview(appearance: Appearance, name: String) {
        var view: View? = null
        var canceled = false
        val state = SolveUiState(conversationId = "c", editingQuestionId = "q-new", editChanged = false,
            input = question("q-new").text)
        compose.setContent { MogeTheme(appearance) {
            view = LocalView.current
            ConversationScaffold("卷积码编码器", null, {}, footer = {
                FollowUpBar(state, {}, {}, {}, {}, {}, {}, {}, onCancelEditing = { canceled = true })
            }) { padding ->
                SolveList(state.copy(items = listOf(
                    SolveItem.Question("root", "请画出 (2,1,3) 卷积码编码器。", emptyList(), "", true),
                    SolveItem.Answer("answer-root", AnswerState.COMPLETED, "采用两级寄存器，码率为 1/2。"),
                    question("q-new", 1),
                )), {}, {}, { _, _ -> }, {}, {}, initialViewport = ConversationViewport("q-new", 2, 0), contentPadding = padding)
            }
        } }
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        compose.onNodeWithTag("conversation-list").performScrollToIndex(2)
        compose.onNodeWithText("2 / 2").assertIsDisplayed()
        val versions = compose.onNodeWithTag("question-versions").fetchSemanticsNode().boundsInRoot
        val footer = compose.onNodeWithTag("conversation-composer").fetchSemanticsNode().boundsInRoot
        assertTrue("Version controls must stay above the composer", versions.bottom <= footer.top)
        compose.waitForIdle()
        compose.runOnIdle {
            val root = requireNotNull(view).rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val output = File("build/branch-previews/$name.png")
                output.parentFile?.mkdirs()
                output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        compose.onNodeWithContentDescription("取消修改").performClick()
        assertTrue(canceled)
    }
}
