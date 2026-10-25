package com.moge.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.createGraph
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.components.PageSwipeSurface
import com.moge.app.ui.components.PaperNavigationLayers
import com.moge.app.ui.components.completePageSwipe
import com.moge.app.ui.solve.*
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
class InteractivePaperNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private lateinit var view: View
    private lateinit var lifecycle: LifecycleRegistry
    private val owners = mutableMapOf<String, NavBackStackEntry>()
    private val models = mutableMapOf<String, Draft>()
    private class Draft : ViewModel() { var text = "待发送的草稿"; var cleared = false
        override fun onCleared() { cleared = true }
    }
    private val factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = Draft() as T
    }

    private fun render(motion: Boolean = true) {
        compose.setContent { MogeTheme {
            CompositionLocalProvider(LocalPaperMotionEnabled provides motion) {
                nav = rememberNavController()
                view = LocalView.current
                lifecycle = LocalLifecycleOwner.current.lifecycle as LifecycleRegistry
                val graph = remember(nav) { nav.createGraph(startDestination = "old") {
                    composable("old") { }; composable("new") { }
                } }
                PaperNavigationLayers {
                    PaperNavHost(nav, graph, null) { entry ->
                        val name = entry.destination.route!!
                        val draft = remember(entry) { ViewModelProvider(entry, factory)[Draft::class.java] }
                        SideEffect { owners[name] = entry; models[name] = draft }
                        PaperSwipeDestination(entry, target = { direction ->
                            if (name == "old" && direction > 0) PaperSwipeTarget("new")
                            else if (name == "new" && direction < 0) PaperSwipeTarget("old", nav.previousBackStackEntry)
                            else null
                        }) {
                            PageSwipeSurface(onRight = if (name == "old") ({ nav.navigate("new") }) else null,
                                onLeft = if (name == "new") ({ nav.popBackStack(); Unit }) else null,
                                modifier = Modifier.testTag("swipe-$name")) {
                                val page = paperConversationPageMotion()
                                ConversationScaffold(name, null, {}, pageMotion = page,
                                    newConversationMode = ConversationMode.SOLVE.takeIf { name == "new" },
                                    footer = { Text(draft.text, Modifier.testTag("draft-$name")) }) {
                                    Box(Modifier.fillMaxSize().background(if (name == "old") Color.Red else Color.Blue)
                                        .testTag("reading-$name")) { Text(name) }
                                }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    private fun startHalfDrag(name: String, direction: Int) {
        compose.onNodeWithTag("swipe-$name").performTouchInput {
            down(Offset(if (direction > 0) width * .2f else width * .8f, height * .55f))
            moveBy(Offset(width * .5f * direction, 0f), delayMillis = 350)
        }
        compose.waitForIdle()
    }

    @Test fun `held half drag shows both pages and titles without moving paper or replacing source draft`() {
        render()
        val source = owners.getValue("old")
        val draft = models.getValue("old")
        val composer = compose.onNodeWithTag("conversation-composer").fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        startHalfDrag("old", 1)
        val oldBody = compose.onNodeWithTag("reading-old").fetchSemanticsNode().boundsInRoot
        val newBody = compose.onNodeWithTag("reading-new").fetchSemanticsNode().boundsInRoot
        assertEquals(view.width / 2f, oldBody.left, 1f)
        assertEquals(view.width / 2f, newBody.right, 1f)
        compose.onAllNodesWithTag("draft-old").assertCountEquals(1)
        compose.onAllNodesWithTag("draft-new").assertCountEquals(0)
        val composers = compose.onAllNodesWithTag("conversation-composer").fetchSemanticsNodes()
        assertTrue(composers.any { it.boundsInRoot == composer })
        compose.runOnIdle {
            assertSame(source, owners["old"])
            assertSame(draft, models["old"])
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            assertEquals(Color.Blue.toArgb(), bitmap.getPixel(view.width / 4, view.height / 2))
            assertEquals(Color.Red.toArgb(), bitmap.getPixel(view.width * 3 / 4, view.height / 2))
            val y = header.center.y.toInt()
            assertNotEquals(Color.Blue.toArgb(), bitmap.getPixel(view.width / 4, y))
            assertNotEquals(Color.Red.toArgb(), bitmap.getPixel(view.width * 3 / 4, y))
            bitmap.recycle()
        }
        // Stop at half a page for another frame: no timer should advance the drag.
        compose.mainClock.advanceTimeBy(1000)
        assertEquals(oldBody, compose.onNodeWithTag("reading-old").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("swipe-old").performTouchInput { moveBy(Offset(-width * .45f, 0f), 350); up() }
        compose.waitForIdle()
        assertSame(source, nav.currentBackStackEntry)
        assertSame(draft, models["old"])
        assertFalse(draft.cleared)
        compose.onNodeWithTag("draft-old").assertTextEquals("待发送的草稿")
        compose.onNodeWithTag("reading-new").assertDoesNotExist()
    }

    @Test fun `committed drag and cancelled previous-page preview keep real entry owners`() {
        render()
        val source = owners.getValue("old")
        val draft = models.getValue("old")
        startHalfDrag("old", 1)
        compose.onNodeWithTag("swipe-old").performTouchInput { advanceEventTime(150); up() }
        compose.waitForIdle()
        assertEquals("new", nav.currentDestination?.route)
        val fresh = owners.getValue("new")
        val newDraft = models.getValue("new")
        startHalfDrag("new", -1)
        compose.onNodeWithTag("swipe-new").performTouchInput { moveBy(Offset(width * .45f, 0f), 350); up() }
        compose.waitForIdle()
        assertSame(fresh, nav.currentBackStackEntry)
        assertSame(newDraft, models["new"])
        assertFalse(newDraft.cleared)
        startHalfDrag("new", -1)
        compose.onNodeWithTag("swipe-new").performTouchInput { advanceEventTime(150); up() }
        compose.waitForIdle()
        assertSame(source, nav.currentBackStackEntry)
        assertSame(draft, models["old"])
        assertFalse(draft.cleared)
        assertTrue(newDraft.cleared)
    }

    @Test fun `disabled animations still follow finger and settle immediately`() {
        render(motion = false)
        startHalfDrag("old", 1)
        assertEquals(view.width / 2f, compose.onNodeWithTag("reading-old").fetchSemanticsNode().boundsInRoot.left, 1f)
        compose.onNodeWithTag("swipe-old").performTouchInput { advanceEventTime(150); up() }
        compose.waitForIdle()
        assertEquals("new", nav.currentDestination?.route)
        compose.onNodeWithTag("reading-old").assertDoesNotExist()
    }

    @Test fun `pointer cancellation removes only the tentative page and preserves the draft owner`() {
        render()
        val source = owners.getValue("old")
        val draft = models.getValue("old")
        startHalfDrag("old", 1)
        val preview = models.getValue("new")
        compose.onNodeWithTag("swipe-old").performTouchInput { cancel() }
        compose.waitForIdle()
        assertSame(source, nav.currentBackStackEntry)
        assertSame(draft, models["old"])
        assertFalse(draft.cleared)
        assertTrue(preview.cleared)
        // A cancelled gesture must not leave an in-progress transition blocking the next one.
        startHalfDrag("old", 1)
        compose.onNodeWithTag("swipe-old").performTouchInput { advanceEventTime(150); up() }
        compose.waitForIdle()
        assertEquals("new", nav.currentDestination?.route)
    }

    @Test fun `release considers distance speed and deliberate reversal`() {
        assertFalse(completePageSwipe(.1f, 0f, 600f))
        assertTrue(completePageSwipe(.4f, 0f, 600f))
        assertTrue(completePageSwipe(.15f, 800f, 600f))
        assertFalse(completePageSwipe(.7f, -800f, 600f))
        assertFalse(completePageSwipe(.01f, 800f, 600f))
    }

    @Test fun `pausing during a drag rolls back the tentative destination before state is saved`() {
        render()
        val source = owners.getValue("old")
        val draft = models.getValue("old")
        startHalfDrag("old", 1)
        compose.runOnIdle { lifecycle.currentState = Lifecycle.State.STARTED }
        compose.waitForIdle()
        assertSame(source, nav.currentBackStackEntry)
        compose.runOnIdle { lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitForIdle()
        compose.onNodeWithTag("swipe-old").performTouchInput { cancel() }
        assertSame(draft, models["old"])
        assertFalse(draft.cleared)
        compose.onNodeWithTag("draft-old").assertTextEquals("待发送的草稿")
    }
}
