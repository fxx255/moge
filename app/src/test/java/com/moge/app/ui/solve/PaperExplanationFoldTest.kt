package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PaperExplanationFoldTest {
    @get:Rule val compose = createComposeRule()
    private fun panelHeight() = compose.onNodeWithTag("fold-panel").fetchSemanticsNode().boundsInRoot.height

    @Test fun `paper opens and closes through intermediate heights rather than jumping`() {
        compose.mainClock.autoAdvance = false
        var expanded by mutableStateOf(false)
        compose.setContent {
            MogeTheme { CompositionLocalProvider(LocalPaperMotionEnabled provides true) {
                PaperExplanationFold(expanded, { expanded = !expanded }, Modifier.testTag("fold-panel")) {
                    Box(Modifier.fillMaxWidth().height(180.dp).testTag("stored-explanation"))
                }
            } }
        }
        val closed = panelHeight()
        compose.runOnIdle { expanded = true }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(112)
        compose.waitForIdle()
        val opening = panelHeight()
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val opened = panelHeight()
        assertTrue("Opening must reveal some paper: $closed -> $opening -> $opened", opening > closed)
        assertTrue("Opening must have a visible intermediate frame", opening < opened)
        compose.runOnIdle { expanded = false }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(112)
        compose.waitForIdle()
        val closing = panelHeight()
        assertTrue(closing < opened && closing > closed)
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(closed, panelHeight(), 1f)
        compose.onNodeWithTag("stored-explanation").assertDoesNotExist()
    }

    @Test fun `disabled motion reveals the stored explanation immediately`() {
        var expanded by mutableStateOf(false)
        compose.setContent {
            MogeTheme { CompositionLocalProvider(LocalPaperMotionEnabled provides false) {
                PaperExplanationFold(expanded, { expanded = !expanded }, Modifier.testTag("fold-panel")) {
                    Box(Modifier.fillMaxWidth().height(180.dp).testTag("stored-explanation"))
                }
            } }
        }
        val closed = panelHeight()
        val beforeOpen = compose.mainClock.currentTime
        compose.runOnIdle { expanded = true }
        compose.onNodeWithTag("stored-explanation").assertExists()
        assertTrue(panelHeight() > closed + 100f)
        assertTrue("Disabled motion must not consume the unfolding duration", compose.mainClock.currentTime - beforeOpen < 150)
        val beforeClose = compose.mainClock.currentTime
        compose.runOnIdle { expanded = false }
        compose.onNodeWithTag("stored-explanation").assertDoesNotExist()
        assertEquals(closed, panelHeight(), 1f)
        assertTrue("Disabled motion must not consume the closing duration", compose.mainClock.currentTime - beforeClose < 150)
    }
}
