package com.moge.app.ui.solve

import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.createGraph
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.components.PageSwipeSurface
import com.moge.app.ui.PaperNavHost
import com.moge.app.ui.PaperSwipeDestination
import com.moge.app.ui.PaperSwipeTarget
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import com.moge.app.ui.components.PaperNavigationLayers
import com.moge.app.ui.markdown.BaselineLatexSpan
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise real asynchronous TeX drawables and actual navigation disposal on MuMu 12-2. */
@RunWith(AndroidJUnit4::class)
class ConversationReturnDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun mathViews(root: View): List<TextView> = when (root) {
        is TextView -> listOf(root)
        is ViewGroup -> (0 until root.childCount).flatMap { mathViews(root.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun centeredFormulaFollowUpSurvivesRepeatedSwipesWithAStationaryComposer() {
        val ribbon = ConversationRibbonState()
        val formula = "\\sigma^2=\\frac{4}{T_s^2}\\int_0^{T_s}\\int_0^{T_s}" +
            "E[n_w(t)n_w(\\tau)]\\cos(2\\pi f_ct)\\cos(2\\pi f_c\\tau)dt\\,d\\tau"
        val follow = SolveItem.Question("follow", "$$\n$formula\n$$\n\n这一步请展开解释。", emptyList(), "", false)
        val items = listOf(
            SolveItem.Question("q", "求输出噪声的方差。", emptyList(), "", true),
            SolveItem.Answer("a", AnswerState.COMPLETED,
                ("先计算自相关函数。\n\n$$\\int_0^T R_n(t)dt$$\n\n").repeat(18)),
            follow,
            SolveItem.Answer("b", AnswerState.COMPLETED, "使用期望的线性性质，交换积分顺序。\n\n".repeat(40)),
        )
        var saved: ConversationViewport? = ConversationViewport("follow", 2,
            -(compose.activity.resources.displayMetrics.heightPixels * 0.26f).toInt())
        lateinit var nav: NavHostController
        compose.setContent { MogeTheme(Appearance.PAPER) {
            nav = rememberNavController()
            PaperNavigationLayers {
            val graph = remember(nav) { nav.createGraph(startDestination = "old") {
                composable("old") { }; composable("new") { }
            } }
            PaperNavHost(nav, graph, null) { entry ->
                val name = entry.destination.route!!
                PaperSwipeDestination(entry, target = { direction ->
                    if (name == "old" && direction > 0) PaperSwipeTarget("new")
                    else if (name == "new" && direction < 0) PaperSwipeTarget("old", nav.previousBackStackEntry)
                    else null
                }) {
                        val motion = paperConversationPageMotion()
                        val state = SolveUiState(conversationId = "c".takeIf { name == "old" },
                            items = if (name == "old") items else emptyList())
                        val initial = remember { saved }
                        PageSwipeSurface(onRight = if (name == "old") ({ nav.navigate("new") }) else null,
                            onLeft = if (name == "new") ({ nav.popBackStack(); Unit }) else null,
                            modifier = Modifier.testTag("interactive-$name")) {
                            ConversationScaffold("随机过程", null, {}, pageMotion = motion, ribbonState = ribbon,
                                newConversationMode = ConversationMode.SOLVE.takeIf { name == "new" }, footer = {
                                FollowUpBar(state, {}, {}, {}, {}, {}, {}, {}, switchingPage = motion.switching,
                                    swipeDirection = motion.direction, swipeProgress = motion.progress)
                            }) { padding ->
                                SolveList(state, {}, {}, { _, _ -> }, {}, {}, initialViewport = initial,
                                    saveViewport = { if (name == "old") saved = it }, contentPadding = padding,
                                    active = motion.active)
                            }
                        }
                    }
            }
            }
        } }
        fun awaitMath() {
            compose.waitUntil(15_000) {
                var loaded = false
                compose.runOnUiThread {
                    val view = mathViews(compose.activity.window.decorView).firstOrNull { it.text.contains("这一步请展开解释") }
                    val text = view?.text as? Spanned
                    val spans = text?.getSpans(0, text.length, BaselineLatexSpan::class.java).orEmpty()
                    loaded = spans.isNotEmpty() && spans.all { it.drawable.hasResult() } &&
                        view?.isLaidOut == true && view.isShown && view.width > 0 && view.height > 0 &&
                        view.getGlobalVisibleRect(android.graphics.Rect())
                }
                loaded
            }
            compose.waitForIdle()
        }
        fun readingTop(): Int {
            val location = IntArray(2)
            compose.runOnUiThread {
                val current = mathViews(compose.activity.window.decorView).single {
                    it.text.contains("这一步请展开解释") && it.isShown &&
                        it.getGlobalVisibleRect(android.graphics.Rect())
                }
                current.getLocationOnScreen(location)
            }
            return location[1]
        }
        fun snapshot(name: String) {
            compose.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try {
                    File(instrumentation.targetContext.getExternalFilesDir(null), name)
                        .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
        awaitMath()
        val originalTop = readingTop()
        val composer = compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot
        snapshot("conversation-before.png")
        repeat(5) {
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("interactive-old").performTouchInput {
                down(Offset(width * .15f, height * .5f))
                moveBy(Offset(width * .5f, 0f), delayMillis = 350)
            }
            compose.mainClock.advanceTimeBy(32)
            // A held half-drag must not turn into an automatic timed page replacement.
            val half = compose.onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot
            assertEquals(compose.activity.resources.displayMetrics.widthPixels / 2f, half.left, 2f)
            val pixels = compose.onRoot().captureToImage().toPixelMap()
            assertNotEquals("The old white answer sheet must leave the left half during a drag",
                Color.White.toArgb(), pixels[40, (pixels.height * .3f).toInt()].toArgb())
            snapshot("interactive-half-$it.png")
            compose.mainClock.advanceTimeBy(160)
            assertEquals(half, compose.onNodeWithTag("conversation-list").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("interactive-old").performTouchInput { advanceEventTime(150); up() }
            compose.mainClock.advanceTimeBy(64)
            compose.onAllNodesWithTag("conversation-input").assertCountEquals(1)
            compose.onAllNodesWithTag("conversation-header").assertCountEquals(1)
            val moving = compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot
            assertEquals(composer.top, moving.top, .1f)
            assertEquals(composer.height, moving.height, .1f)
            assertTrue("The composer only drifts slightly in the swipe direction",
                moving.left - composer.left in 0f..(4f * compose.activity.resources.displayMetrics.density))
            snapshot("conversation-switch-$it.png")
            compose.mainClock.advanceTimeBy(800)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            compose.onAllNodesWithTag("conversation-input").assertCountEquals(1)
            assertEquals(composer, compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("conversation-list").assertDoesNotExist()
            snapshot("conversation-new-$it.png")
            compose.onRoot().performTouchInput { swipeLeft(durationMillis = 180) }
            compose.runOnIdle { assertEquals("Return swipe must navigate to the old page", "old", nav.currentDestination?.route) }
            awaitMath()
            snapshot("conversation-return-$it.png")
            assertEquals("Native formula after return $it, saved=$saved", originalTop.toFloat(), readingTop().toFloat(), 1f)
            assertEquals(composer, compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot)
            assertEquals("follow", saved?.messageId)
        }
    }
}
