package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.domain.ConversationMode
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
class ConversationRibbonTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `first submission moves once and title streaming and followups never reverse the paper`() {
        var existing by mutableStateOf(false)
        var title by mutableStateOf("")
        var answer by mutableStateOf("")
        val ribbon = ConversationRibbonState(false)
        compose.setContent { MogeTheme { CompositionLocalProvider(LocalPaperMotionEnabled provides true) {
            ConversationScaffold(title, null, actions = {}, ribbonState = ribbon,
                newConversationMode = ConversationMode.SOLVE.takeUnless { existing }, footer = {
                    FollowUpBar(SolveUiState(), {}, {}, {}, {}, {}, {}, {})
                }) { Box(Modifier.fillMaxSize()) { Text(answer) } }
        } } }
        compose.waitForIdle()
        val composer = compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, ribbon.progress.value, 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { existing = true }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(144)
        compose.waitForIdle()
        val first = ribbon.progress.value
        assertTrue("Submission must start the single upward motion: $first", first > 0f && first < 1f)
        compose.runOnIdle { title = "矩阵乘法的计算与证明"; answer = "开始生成" }
        compose.mainClock.advanceTimeBy(144)
        compose.waitForIdle()
        assertTrue("Title generation must continue the original motion", ribbon.progress.value > first)
        compose.runOnIdle { answer = "回答继续生成" }
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        assertEquals(1f, ribbon.progress.value, 0f)
        val finalHeader = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        assertEquals(header.left, finalHeader.left, .1f)
        assertEquals(header.height, finalHeader.height, .1f)
        assertTrue(finalHeader.top < header.top)
        repeat(3) { index ->
            compose.runOnIdle { answer = if (index == 0) "回答结束" else "追问 $index 的回答" }
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            assertEquals(1f, ribbon.progress.value, 0f)
            assertEquals(finalHeader, compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot)
            assertEquals(composer, compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot)
        }
        compose.mainClock.autoAdvance = true
    }

    @Test fun `rapid reversals follow the destination and disabling motion settles immediately`() {
        var existing by mutableStateOf(true)
        var motion by mutableStateOf(true)
        val ribbon = ConversationRibbonState(true)
        compose.setContent { MogeTheme { CompositionLocalProvider(LocalPaperMotionEnabled provides motion) {
            ConversationScaffold("已保存的对话", null, actions = {}, footer = {}, ribbonState = ribbon,
                newConversationMode = ConversationMode.CHAT.takeUnless { existing }) {}
        } } }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { existing = false }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(144)
        compose.waitForIdle()
        val towardNew = ribbon.progress.value
        assertTrue("A reversal must start toward the new page: $towardNew", towardNew > 0f && towardNew < 1f)
        compose.runOnIdle { existing = true }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals(1f, ribbon.progress.value, 0f)
        compose.runOnIdle { existing = false }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(144)
        compose.waitForIdle()
        compose.runOnIdle { motion = false }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        assertEquals(0f, ribbon.progress.value, 0f)
        compose.runOnIdle { existing = true }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        assertEquals(1f, ribbon.progress.value, 0f)
        compose.mainClock.autoAdvance = true
    }
}
