package com.moge.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.createGraph
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.components.PageSwipeSurface
import com.moge.app.ui.components.PaperNavigationLayers
import com.moge.app.ui.components.PaperScaffold
import com.moge.app.ui.solve.ConversationScaffold
import com.moge.app.ui.solve.paperConversationPageMotion
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
class ComposerNavigationTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var nav: NavHostController
    private lateinit var view: View

    @Test fun `history drag lowers the input and returning raises the same input`() =
        verify("history", 1, Appearance.PAPER, 72)

    @Test fun `notebook drag clears a tall attachment tray in the other direction and theme`() =
        verify("notebook", -1, Appearance.CHALK, 168)

    @Test fun `disabled animations still track the finger and cancellation restores the input`() =
        verify("history", 1, Appearance.PAPER, 120, motion = false)

    private fun verify(list: String, direction: Int, appearance: Appearance, footerHeight: Int, motion: Boolean = true) {
        compose.setContent { MogeTheme(appearance) {
            CompositionLocalProvider(LocalPaperMotionEnabled provides motion) {
                nav = rememberNavController()
                view = LocalView.current
                val graph = remember(nav) { nav.createGraph(startDestination = "conversation") {
                    composable("conversation") { }; composable(list) { }
                } }
                PaperNavigationLayers {
                    PaperNavHost(nav, graph, null, hasComposer = { it.destination.route == "conversation" }) { entry ->
                        val conversation = entry.destination.route == "conversation"
                        val next = if (conversation) list else "conversation"
                        val dragDirection = if (conversation) direction else -direction
                        PaperSwipeDestination(entry, target = { swipeDirection ->
                            if (swipeDirection == dragDirection) PaperSwipeTarget(next,
                                nav.previousBackStackEntry?.takeIf { it.destination.route == next }) else null
                        }) {
                            PageSwipeSurface(onLeft = ({}).takeIf { dragDirection < 0 },
                                onRight = ({}).takeIf { dragDirection > 0 }) {
                                if (conversation) ConversationScaffold("题目", null, {},
                                    newConversationMode = ConversationMode.SOLVE,
                                    pageMotion = paperConversationPageMotion(), footer = {
                                        Box(Modifier.fillMaxWidth().height(footerHeight.dp).background(Color.Magenta)
                                            .testTag("input-ink")) { Text("尚未发送的草稿") }
                                    }) { Box(Modifier.fillMaxSize().background(Color.Cyan)) }
                                else PaperScaffold(list, {}) { Box(Modifier.fillMaxSize().background(Color.Cyan)) }
                            }
                        }
                    }
                }
            }
        } }
        compose.waitForIdle()
        fun top() = compose.onNodeWithTag("input-ink").fetchSemanticsNode().positionInRoot.y
        fun pixel(y: Float): Int {
            var result = 0
            compose.runOnIdle {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    result = bitmap.getPixel(view.width / 2, y.toInt())
                } finally { bitmap.recycle() }
            }
            return result
        }
        fun drag(sign: Int, fraction: Float) {
            compose.onRoot().performTouchInput {
                down(Offset(if (sign > 0) width * .15f else width * .85f, height * .5f))
                moveBy(Offset(width * fraction * sign, 0f), delayMillis = 350)
            }
            compose.waitForIdle()
        }
        fun release() {
            compose.onRoot().performTouchInput { advanceEventTime(150); up() }
            compose.waitForIdle()
        }
        val rest = top()
        assertEquals(Color.Magenta.toArgb(), pixel(rest + 4))
        drag(direction, .25f)
        val quarter = top()
        assertTrue("The input moves down before navigation completes", quarter > rest)
        assertEquals("Recorded overlay ink moves with its hit geometry", Color.Cyan.toArgb(), pixel(rest + 4))
        assertEquals(Color.Magenta.toArgb(), pixel(quarter + 4))
        compose.onRoot().performTouchInput { moveBy(Offset(width * .25f * direction, 0f), 350) }
        compose.waitForIdle()
        val half = top()
        assertEquals("Distance follows the held finger linearly", (quarter - rest) * 2, half - rest, 1f)
        compose.mainClock.advanceTimeBy(600)
        assertEquals("A held drag must not run a separate timed exit", half, top(), .1f)
        compose.onRoot().performTouchInput { moveBy(Offset(-width * .45f * direction, 0f), 350); up() }
        compose.waitForIdle()
        assertEquals("conversation", nav.currentDestination?.route)
        assertEquals(rest, top(), .1f)
        assertEquals(Color.Magenta.toArgb(), pixel(rest + 4))

        drag(direction, .5f)
        release()
        assertEquals(list, nav.currentDestination?.route)
        compose.onNodeWithTag("input-ink").assertDoesNotExist()
        drag(-direction, .5f)
        assertEquals("The incoming input follows the same path in reverse", half, top(), 1f)
        compose.onRoot().performTouchInput { cancel() }
        compose.waitForIdle()
        assertEquals(list, nav.currentDestination?.route)
        compose.onNodeWithTag("input-ink").assertDoesNotExist()
        drag(-direction, .5f)
        release()
        assertEquals("conversation", nav.currentDestination?.route)
        assertEquals(rest, top(), .1f)
        compose.onNodeWithText("尚未发送的草稿").assertExists()
    }
}
