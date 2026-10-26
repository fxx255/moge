package com.moge.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.ui.components.*
import com.moge.app.ui.solve.FollowUpBar
import com.moge.app.ui.solve.SolveUiState
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h820dp")
@OptIn(ExperimentalTestApi::class)
class PaperControlMotionTest {
    @get:Rule val compose = createComposeRule(StandardTestDispatcher())

    @Test fun `controls sway in opposite horizontal directions and share sixty percent vertical travel`() = verify(true)
    @Test fun `system disabled motion keeps both controls stationary`() = verify(false)

    private fun verify(motion: Boolean) {
        var progress by mutableFloatStateOf(0f)
        var direction by mutableIntStateOf(1)
        lateinit var sway: PaperControlSway
        var density = 1f
        compose.setContent { MogeTheme {
            CompositionLocalProvider(LocalPaperMotionEnabled provides motion,
                LocalPaperPageMotion provides PaperPageMotion(switching = true, swipeDirection = direction,
                    swipeProgress = { progress })) {
                density = LocalDensity.current.density
                sway = rememberPaperControlSway()
                Column {
                    PaperSearchPanel("", {}, "搜索", true, scrollSway = { sway.fraction }) {}
                    FollowUpBar(SolveUiState(), {}, {}, {}, {}, {}, {}, {}, switchingPage = true,
                        swipeDirection = direction, swipeProgress = { progress }, scrollSway = { sway.fraction })
                }
            }
        } }
        val panel = compose.onNodeWithTag("paper-search-panel").fetchSemanticsNode().boundsInRoot
        // The outer surface tag is before its graphics layer; inspect the live
        // input inside that layer to measure its actual displayed position.
        val composer = compose.onNodeWithTag("conversation-input").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        for (sign in listOf(1, -1)) {
            compose.runOnUiThread { direction = sign; progress = .5f }
            compose.mainClock.advanceTimeByFrame()
            val p = compose.onNodeWithTag("paper-search-panel").fetchSemanticsNode().boundsInRoot
            val c = compose.onNodeWithTag("conversation-input").fetchSemanticsNode().boundsInRoot
            assertEquals(if (motion) -3f * density * sign else 0f, p.left - panel.left, .1f)
            assertEquals(if (motion) 3f * density * sign else 0f, c.left - composer.left, .1f)
        }
        compose.runOnUiThread {
            assertEquals(Offset.Zero, sway.onPreScroll(Offset(0f, 100f * density), NestedScrollSource.UserInput))
        }
        compose.mainClock.advanceTimeBy(96)
        val movedPanel = compose.onNodeWithTag("paper-search-panel").fetchSemanticsNode().boundsInRoot
        val movedComposer = compose.onNodeWithTag("conversation-input").fetchSemanticsNode().boundsInRoot
        assertEquals(if (motion) 1.8f * density else 0f, movedPanel.top - panel.top, .15f)
        assertEquals(movedPanel.top - panel.top, movedComposer.top - composer.top, .1f)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals(panel.top, compose.onNodeWithTag("paper-search-panel").fetchSemanticsNode().boundsInRoot.top, .1f)
        compose.runOnUiThread { progress = 1f }
        compose.mainClock.advanceTimeByFrame()
        assertEquals(panel.left, compose.onNodeWithTag("paper-search-panel").fetchSemanticsNode().boundsInRoot.left, .1f)
        compose.mainClock.autoAdvance = true
    }
}
