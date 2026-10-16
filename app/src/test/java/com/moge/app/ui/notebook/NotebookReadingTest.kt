package com.moge.app.ui.notebook

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.data.db.NotebookEntryEntity
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.components.PaperPageReveal
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotebookReadingTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `saved answer scrolls under the fixed title while the grid moves with the text`() {
        val entry = NotebookEntryEntity(id = "saved", sourceConversationId = "source", sourceQuestionId = "q",
            sourceAnswerId = "a", title = "幂级数展开求和", questionText = "计算幂级数的和函数",
            answerText = (1..60).joinToString("\n\n") { "第 $it 步：求导后积分，核对积分常数与收敛区间。" })
        lateinit var view: View
        compose.setContent { MogeTheme(Appearance.CHALK) {
            view = LocalView.current
            CompositionLocalProvider(LocalPaperMotionEnabled provides false) {
                NotebookContent(NotebookUiState(loading = false, openEntryId = entry.id, detailEntry = entry),
                    onBack = {}, onOpenConversation = {}, onQuery = {}, onCategory = { _, _ -> },
                    onToggleSelection = {}, onClearSelection = {}, onSelectAll = {}, onOpenEntry = {}, onCloseEntry = {},
                    onMove = {}, onDelete = {}, onCreateCategory = {}, onRenameCategory = { _, _ -> },
                    onReorderCategory = { _, _ -> }, onDeleteCategory = {}, onRetry = {}, onDismissMessage = {})
            }
        } }
        val title = compose.onNodeWithText(entry.title)
        val headerBefore = title.fetchSemanticsNode().boundsInRoot
        val body = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("题目")))
        assertTrue(body.fetchSemanticsNode().boundsInRoot.top < headerBefore.bottom)
        fun snapshot(): Bitmap {
            lateinit var bitmap: Bitmap
            compose.runOnIdle {
                bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
            }
            return bitmap
        }
        val before = snapshot()
        body.performTouchInput {
            down(center); moveBy(Offset(0f, -87f), delayMillis = 160); advanceEventTime(200); up()
        }
        compose.waitForIdle()
        assertEquals(headerBefore, title.fetchSemanticsNode().boundsInRoot)
        val after = snapshot()
        try {
            val x = (8 * view.resources.displayMetrics.density).toInt()
            val start = headerBefore.bottom.toInt() + 70
            val changed = (start until before.height - 60).count { before.getPixel(x, it) != after.getPixel(x, it) }
            assertTrue("The grid must move during saved-answer reading", changed > 8)
        } finally { before.recycle(); after.recycle() }
    }

    @Test fun `entry unfolds once and remains immediately accessible when motion is disabled`() {
        val id = mutableStateOf<String?>(null)
        val motion = mutableStateOf(true)
        compose.setContent { MogeTheme {
            CompositionLocalProvider(LocalPaperMotionEnabled provides motion.value) {
                PaperPageReveal(id.value, Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().testTag("page")) { Text("题目正文") } }
            }
        } }
        val final = compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle { id.value = "opened" }
            repeat(2) { compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
            val opening = compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
            assertTrue("Opening must visibly expand the page", opening.width < final.width - 1f)
            compose.mainClock.advanceTimeBy(300)
            compose.waitForIdle()
            assertEquals(final, compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot)
            compose.runOnIdle { motion.value = false; id.value = "reduced-motion" }
            compose.waitForIdle()
            compose.onNodeWithText("题目正文").assertIsDisplayed()
            assertEquals(final, compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot)
        } finally { compose.mainClock.autoAdvance = true }
    }
}
