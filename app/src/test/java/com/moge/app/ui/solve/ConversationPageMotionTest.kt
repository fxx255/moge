package com.moge.app.ui.solve

import android.app.Application
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.domain.ConversationMode
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
class ConversationPageMotionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `only reading layers slide and each conversation keeps its own stationary draft`() = verify(true)
    @Test fun `disabled motion swaps content without moving or duplicating composer`() = verify(false)

    private fun verify(motion: Boolean) {
        lateinit var nav: NavHostController
        val ribbon = ConversationRibbonState()
        compose.setContent { MogeTheme {
            CompositionLocalProvider(LocalPaperMotionEnabled provides motion) {
                nav = rememberNavController()
                NavHost(nav, startDestination = "old", enterTransition = { EnterTransition.None },
                    exitTransition = { ExitTransition.None }, popEnterTransition = { EnterTransition.None },
                    popExitTransition = { ExitTransition.None }) {
                    listOf("old", "new").forEach { name ->
                        composable(name) {
                            val page = conversationPageMotion(1, stationaryComposer = true)
                            val state = remember { SolveUiState(input = if (name == "old") "原对话草稿" else "新对话草稿") }
                            ConversationScaffold(name, null, {}, pageMotion = page, ribbonState = ribbon,
                                newConversationMode = ConversationMode.SOLVE.takeIf { name == "new" }, footer = {
                                FollowUpBar(state, {}, {}, {}, {}, {}, {}, {}, switchingPage = page.switching)
                            }) {
                                Box(Modifier.fillMaxSize().testTag("reading-$name")) { Text(name) }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        val composer = compose.onNodeWithTag("conversation-composer").fetchSemanticsNode().boundsInRoot
        val surface = compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        assertEquals(1f, ribbon.progress.value, 0f)
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { nav.navigate("new") }
        compose.mainClock.advanceTimeBy(32)
        repeat(4) {
            compose.onAllNodesWithTag("conversation-input").assertCountEquals(1)
            compose.onAllNodesWithTag("conversation-header").assertCountEquals(1)
            assertEquals(header.left, compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot.left, .1f)
            compose.onNodeWithText("新对话草稿").assertExists()
            val current = compose.onAllNodesWithTag("conversation-composer").fetchSemanticsNodes()
            assertTrue(current.isNotEmpty())
            current.forEach { assertEquals(composer.left, it.boundsInRoot.left, 0.1f) }
            assertEquals(surface, compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot)
            if (motion && it == 0) {
                assertTrue(compose.onNodeWithTag("reading-new").fetchSemanticsNode().boundsInRoot.left < 0f)
            }
            compose.mainClock.advanceTimeBy(48)
        }
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithTag("reading-old").assertDoesNotExist()
        assertEquals(0f, ribbon.progress.value, 0f)
        compose.runOnIdle { nav.popBackStack() }
        compose.mainClock.advanceTimeBy(600)
        assertEquals(1f, ribbon.progress.value, 0f)
        compose.onNodeWithText("原对话草稿").assertExists()
        compose.onAllNodesWithTag("conversation-input").assertCountEquals(1)
        compose.mainClock.autoAdvance = true
    }
}
