package com.moge.app.ui.components

import android.app.Application
import android.content.Context
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
class PageSwipeTest {
    @get:Rule val compose = createComposeRule()

    private val enabled = mutableStateOf(true)
    private var leftCalls = 0
    private var rightCalls = 0
    private var density = 1f
    private var longPressTimeoutMillis = 500L

    private fun render(
        leftAvailable: Boolean = true,
        rightAvailable: Boolean = true,
        leftLabel: String = "上一页",
        rightLabel: String = "下一页",
        content: @Composable () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                density = LocalDensity.current.density
                longPressTimeoutMillis = LocalViewConfiguration.current.longPressTimeoutMillis
                PageSwipeSurface(
                    enabled = enabled.value,
                    onLeft = if (leftAvailable) ({ leftCalls++ }) else null,
                    onRight = if (rightAvailable) ({ rightCalls++ }) else null,
                    leftLabel = leftLabel,
                    rightLabel = rightLabel,
                    modifier = Modifier.testTag("page-swipe"),
                    content = content,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun pixels(dp: Float) = dp * density

    private fun gesture(dxDp: Float, dyDp: Float = 0f, tag: String = "page-swipe") {
        compose.onNodeWithTag(tag).performTouchInput {
            val start = center
            down(start)
            moveTo(start + Offset(pixels(dxDp), pixels(dyDp)), delayMillis = 80)
            up()
        }
        compose.waitForIdle()
    }

    private fun assertCalls(left: Int = 0, right: Int = 0) {
        compose.runOnIdle {
            assertEquals("Left navigation count", left, leftCalls)
            assertEquals("Right navigation count", right, rightCalls)
        }
    }

    @Test fun `left swipe fires once on release even after multiple moves beyond threshold`() {
        render()
        compose.onNodeWithTag("page-swipe").performTouchInput {
            val start = center
            down(start)
            moveTo(start + Offset(pixels(-80f), 0f), delayMillis = 80)
            moveTo(start + Offset(pixels(-96f), 0f), delayMillis = 40)
        }
        assertCalls()
        compose.onNodeWithTag("page-swipe").performTouchInput {
            moveBy(Offset(pixels(-4f), 0f), delayMillis = 40)
            up()
        }
        assertCalls(left = 1)
        compose.waitForIdle()
        assertCalls(left = 1)
    }

    @Test fun `right swipe fires once on release even after multiple moves beyond threshold`() {
        render()
        compose.onNodeWithTag("page-swipe").performTouchInput {
            val start = center
            down(start)
            moveTo(start + Offset(pixels(80f), 0f), delayMillis = 80)
            moveTo(start + Offset(pixels(96f), 0f), delayMillis = 40)
        }
        assertCalls()
        compose.onNodeWithTag("page-swipe").performTouchInput { up() }
        assertCalls(right = 1)
        compose.waitForIdle()
        assertCalls(right = 1)
    }

    @Test fun `displacements just below 72dp do not navigate in either direction`() {
        render()
        gesture(-71f)
        gesture(71f)
        assertCalls()
    }

    @Test fun `72dp boundary navigates in either direction`() {
        render()
        gesture(-72f)
        assertCalls(left = 1)
        gesture(72f)
        assertCalls(left = 1, right = 1)
    }

    @Test fun `crossing threshold then retreating below it before release does not navigate`() {
        render()
        compose.onNodeWithTag("page-swipe").performTouchInput {
            val start = center
            down(start)
            moveTo(start + Offset(pixels(-96f), 0f), delayMillis = 80)
            moveTo(start + Offset(pixels(-40f), 0f), delayMillis = 40)
            up()
        }
        assertCalls()
    }

    @Test fun `vertical child scroll remains usable without page navigation`() {
        val scroll = ScrollState(0)
        render {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).testTag("vertical-child")) {
                repeat(40) { Text("Scrollable row $it", Modifier.fillMaxWidth().height(48.dp)) }
            }
        }
        compose.onNodeWithTag("vertical-child").performTouchInput { swipeUp(durationMillis = 160) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("The vertical list must actually scroll", scroll.value > 0) }
        assertCalls()
    }

    @Test fun `excluded horizontal child scrolls both ways without navigating the page`() {
        val scroll = ScrollState(0)
        render {
            Column {
                Row(Modifier.fillMaxWidth().height(96.dp).excludePageSwipe()
                    .horizontalScroll(scroll).testTag("horizontal-child")) {
                    repeat(12) { Text("Horizontal cell $it", Modifier.width(160.dp)) }
                }
            }
        }
        compose.onNodeWithTag("horizontal-child").performTouchInput { swipeLeft(durationMillis = 160) }
        compose.waitForIdle()
        var afterLeft = 0
        compose.runOnIdle {
            afterLeft = scroll.value
            assertTrue("The excluded row must actually scroll left", afterLeft > 0)
        }
        compose.onNodeWithTag("horizontal-child").performTouchInput { swipeRight(durationMillis = 160) }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue("The excluded row must actually scroll right", scroll.value < afterLeft) }
        assertCalls()
        // The exclusion covers the child, rather than disabling the whole surface.
        gesture(-96f)
        assertCalls(left = 1)
    }

    @Test fun `excluded text input remains editable and horizontal gestures do not navigate`() {
        val text = mutableStateOf("Editable content ".repeat(12))
        render {
            BasicTextField(
                value = text.value,
                onValueChange = { text.value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().height(72.dp).excludePageSwipe().testTag("excluded-input"),
            )
        }
        compose.onNodeWithTag("excluded-input").performClick().performTextInput("typed")
        compose.runOnIdle { assertTrue("The excluded input must still accept typing", text.value.contains("typed")) }
        gesture(-96f, tag = "excluded-input")
        gesture(96f, tag = "excluded-input")
        assertCalls()
    }

    @Test fun `small jitter and diagonal motion do not navigate`() {
        render()
        gesture(4f, 3f)
        gesture(-4f, -3f)
        gesture(96f, 80f)
        gesture(-96f, -80f)
        assertCalls()
    }

    @Test fun `initially horizontal gesture that becomes diagonal is rejected on release`() {
        render()
        compose.onNodeWithTag("page-swipe").performTouchInput {
            val start = center
            down(start)
            moveTo(start + Offset(pixels(40f), 0f), delayMillis = 80)
            moveTo(start + Offset(pixels(96f), pixels(80f)), delayMillis = 40)
            up()
        }
        assertCalls()
    }

    @Test fun `gesture starting vertically cannot later become page navigation`() {
        render()
        compose.onNodeWithTag("page-swipe").performTouchInput {
            val start = center
            down(start)
            moveTo(start + Offset(0f, pixels(40f)), delayMillis = 80)
            moveTo(start + Offset(pixels(96f), pixels(40f)), delayMillis = 40)
            up()
        }
        assertCalls()
    }

    @Test fun `long press followed by horizontal movement does not navigate`() {
        render()
        compose.onNodeWithTag("page-swipe").performTouchInput {
            down(center)
            advanceEventTime(longPressTimeoutMillis + 32)
            moveBy(Offset(pixels(-96f), 0f), delayMillis = 16)
            up()
        }
        assertCalls()
        gesture(-96f)
        assertCalls(left = 1)
    }

    @Test fun `second pointer aborts a swipe already past threshold until both pointers are up`() {
        render()
        compose.onNodeWithTag("page-swipe").performTouchInput {
            val start = center
            down(pointerId = 0, position = start)
            moveBy(pointerId = 0, delta = Offset(pixels(-80f), 0f), delayMillis = 80)
            down(pointerId = 1, position = start + Offset(0f, pixels(24f)))
            up(pointerId = 1)
            moveBy(pointerId = 0, delta = Offset(pixels(-16f), 0f), delayMillis = 40)
            up(pointerId = 0)
        }
        assertCalls()
        gesture(96f)
        assertCalls(right = 1)
    }

    @Test fun `disabled surface rejects both swipe directions and exposes no actions`() {
        enabled.value = false
        render()
        gesture(-96f)
        gesture(96f)
        assertCalls()
        val actions = compose.onNodeWithTag("page-swipe").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(actions.isEmpty())
    }

    @Test fun `disabling during an active swipe prevents navigation on release`() {
        render()
        compose.onNodeWithTag("page-swipe").performTouchInput {
            down(center)
            moveBy(Offset(pixels(-96f), 0f), delayMillis = 80)
        }
        assertCalls()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("page-swipe").performTouchInput { up() }
        assertCalls()
        compose.runOnIdle { enabled.value = true }
        gesture(-96f)
        assertCalls(left = 1)
    }

    @Test fun `accessibility uses supplied labels and actions invoke the matching callbacks`() {
        render(leftLabel = "查看历史记录", rightLabel = "查看笔记本")
        val actions = compose.onNodeWithTag("page-swipe").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("查看历史记录", "查看笔记本"), actions.map { it.label })
        compose.runOnIdle {
            assertTrue(actions[0].action())
            assertEquals(1, leftCalls)
            assertEquals(0, rightCalls)
            assertTrue(actions[1].action())
        }
        assertCalls(left = 1, right = 1)
    }

    @Test fun `default accessibility labels describe left and right page changes`() {
        compose.setContent {
            PageSwipeSurface(onLeft = { leftCalls++ }, onRight = { rightCalls++ }, modifier = Modifier.testTag("page-swipe")) {}
        }
        val actions = compose.onNodeWithTag("page-swipe").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("向左切换页面", "向右切换页面"), actions.map { it.label })
    }

    @Test fun `missing callback removes its accessibility action and ignores its swipe direction`() {
        render(leftAvailable = false)
        val actions = compose.onNodeWithTag("page-swipe").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("下一页"), actions.map { it.label })
        gesture(-96f)
        assertCalls()
        gesture(96f)
        assertCalls(right = 1)
    }

    @Test fun `disposing excluded child makes its former region navigable`() {
        val visible = mutableStateOf(true)
        render {
            if (visible.value) Box(Modifier.fillMaxSize().excludePageSwipe().testTag("temporary-exclusion"))
        }
        gesture(-96f)
        assertCalls()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("temporary-exclusion").assertDoesNotExist()
        gesture(-96f)
        assertCalls(left = 1)
    }

    private class SelectableTextView(context: Context) : TextView(context) {
        var consumedDown = false
            private set

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val consumed = super.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_DOWN) consumedDown = consumed
            return consumed
        }
    }

    @Test fun `excluded native selectable text consumes down but never navigates`() {
        lateinit var native: SelectableTextView
        render {
            AndroidView(
                factory = { context -> SelectableTextView(context).also {
                    native = it
                    it.text = "Native selectable text with a complete answer and a second line.\nLong press can select these words."
                    it.setTextIsSelectable(true)
                } },
                modifier = Modifier.fillMaxWidth().height(120.dp).excludePageSwipe().testTag("native-selectable"),
            )
        }
        gesture(-96f, tag = "native-selectable")
        gesture(96f, tag = "native-selectable")
        compose.runOnIdle {
            assertTrue("The native selection path must be enabled", native.isTextSelectable)
            assertTrue("The test must exercise a native TextView that consumes DOWN", native.consumedDown)
        }
        assertCalls()
    }

    @Test fun `unexcluded native selectable text does not block page swipe before consuming down`() {
        lateinit var native: SelectableTextView
        render {
            AndroidView(
                factory = { context -> SelectableTextView(context).also {
                    native = it
                    it.text = "Native selectable answer text. Swipe here to change the page."
                    it.setTextIsSelectable(true)
                } },
                modifier = Modifier.fillMaxWidth().height(120.dp).testTag("native-selectable"),
            )
        }
        gesture(-96f, tag = "native-selectable")
        compose.runOnIdle {
            assertTrue(native.isTextSelectable)
            assertTrue("Native DOWN consumption must not hide the swipe from its parent", native.consumedDown)
        }
        assertCalls(left = 1)
    }
}
