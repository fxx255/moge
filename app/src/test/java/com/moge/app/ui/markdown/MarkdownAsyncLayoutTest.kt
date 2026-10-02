package com.moge.app.ui.markdown

import android.app.Application
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercise real AndroidView relayout and touch dispatch, including delayed span sizes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class MarkdownAsyncLayoutTest {
    @get:Rule val compose = createComposeRule()

    private class SizedSpan(var pixels: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int,
            fm: Paint.FontMetricsInt?): Int {
            fm?.apply { ascent = -pixels; top = -pixels; descent = 0; bottom = 0 }
            return 40
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint) = Unit
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun `late span growth and shrink update bounds without rerendering markdown`() {
        lateinit var root: View
        var answer = Rect.Zero
        var thinking = Rect.Zero
        compose.setContent {
            root = LocalView.current
            LazyColumn(Modifier.width(300.dp).height(500.dp), reverseLayout = true) {
                item {
                    Column {
                        Box(Modifier.onGloballyPositioned { answer = it.boundsInRoot() }) {
                            MarkdownChunk("ASYNC_HOST", null)
                        }
                        Box(Modifier.fillMaxWidth().height(80.dp)
                            .onGloballyPositioned { thinking = it.boundsInRoot() })
                    }
                }
            }
        }
        compose.waitForIdle()
        lateinit var host: TextView
        val span = SizedSpan(30)
        val body = SpannableString("x").apply { setSpan(span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        compose.runOnIdle {
            host = textViews(root.rootView).single { it.text.toString() == "ASYNC_HOST" }
            host.text = body
        }
        compose.waitForIdle()
        val smallHeight = host.height
        // Past the previous polling deadline: an async drawable still must be allowed to resize.
        compose.mainClock.advanceTimeBy(13_000)
        compose.runOnIdle { span.pixels = 240; host.text = host.text }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("Late drawable growth was clipped", host.height > smallHeight + 150)
            assertEquals(host.height.toFloat(), answer.height, 1f)
            assertTrue("Thinking overlaps the answer: $answer / $thinking", thinking.top >= answer.bottom - 1)
            assertSame(span, (host.text as Spanned).getSpans(0, 1, SizedSpan::class.java).single())
        }
        compose.runOnIdle { span.pixels = 30; host.text = host.text }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(smallHeight, host.height) }
    }

    @Test
    fun `image and table stay interactive below a multi screen AndroidView`() {
        lateinit var root: View
        lateinit var scroll: ScrollState
        var clicks = 0
        compose.setContent {
            root = LocalView.current
            val tableWidthPx = with(LocalDensity.current) { 540.dp.roundToPx() }
            LazyColumn(Modifier.width(300.dp).height(500.dp), reverseLayout = true) {
                item {
                    Column {
                        MarkdownChunk("LONG_HOST", null)
                        scroll = rememberScrollState()
                        Box(Modifier.horizontalScroll(scroll).testTag("table")) {
                            MarkdownChunk("| A | B |\n| --- | --- |\n| C | D |", tableWidthPx, false)
                        }
                        Box(Modifier.testTag("image")) {
                            InlineFigure("/missing/test.png", onClick = { clicks++ })
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val host = textViews(root.rootView).single { it.text.toString() == "LONG_HOST" }
            // Deterministic multi-screen text height even with Robolectric's stub font metrics.
            host.text = SpannableString("x").apply {
                setSpan(SizedSpan(6000), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("image").performTouchInput { click() }
        compose.onNodeWithTag("table").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertTrue("Image did not receive the tap", clicks > 0)
            assertTrue("Table did not receive the horizontal drag", scroll.value > 0)
        }
    }
}
