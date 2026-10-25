package com.moge.app.ui.solve

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.text.Spanned
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.markdown.BaselineLatexSpan
import com.moge.app.ui.markdown.formulaSelectionParts
import com.moge.app.ui.markdown.markdownSelectionController
import com.moge.app.ui.markdown.selectedMarkdownText
import com.moge.app.ui.markdown.selectionEndpoint
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class MarkdownSelectionRefinementDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun views(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun inject(action: Int, x: Float, y: Float, time: Long) {
        val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream()
                    .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }

    private fun awaitFrame(view: View) {
        val frame = CountDownLatch(1)
        instrumentation.runOnMainSync { view.postOnAnimation { frame.countDown() } }
        assertTrue("Native popup placement must present a frame", frame.await(2, TimeUnit.SECONDS))
        instrumentation.runOnMainSync { }
    }

    @Test fun nestedFormulaLongPressHandleDragAndCopyWorkInBothThemes() {
        val appearance = mutableStateOf(Appearance.PAPER)
        val fraction = "\\frac{\\pi f}{B}"
        val formula = "N_0\\left[1+\\cos\\left($fraction\\right)\\right]"
        compose.setContent { MogeTheme(appearance.value) {
            SolveList(SolveUiState(conversationId = "test", items = listOf(
                SolveItem.Answer("answer", AnswerState.COMPLETED, "\$\$\n$formula\n\$\$"))),
                {}, {}, { _, _ -> }, {}, {})
        } }
        for (theme in listOf(Appearance.PAPER, Appearance.CHALK)) {
            compose.runOnUiThread { appearance.value = theme }
            compose.waitForIdle()
            lateinit var view: TextView
            lateinit var span: BaselineLatexSpan
            compose.waitUntil(15_000) {
                var ready = false
                compose.runOnUiThread {
                    views(compose.activity.window.decorView).forEach { candidate ->
                        val text = candidate.text as? Spanned
                        val math = text?.getSpans(0, text.length, BaselineLatexSpan::class.java)?.singleOrNull()
                        if (candidate.isLaidOut && candidate.isShown && math?.drawable?.hasResult() == true &&
                            candidate.getGlobalVisibleRect(android.graphics.Rect())) {
                            view = candidate; span = math; ready = true
                        }
                    }
                }
                ready
            }
            try {
                var point = 0f to 0f
                var fractionEnd = 0
                compose.runOnUiThread {
                    assertEquals(android.view.Display.DEFAULT_DISPLAY, view.display.displayId)
                    val part = formulaSelectionParts(view, span).single {
                        selectedMarkdownText(view.text, it.start, it.end) == fraction
                    }
                    fractionEnd = part.end
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    point = location[0] + part.bounds.centerX() to location[1] + part.bounds.centerY()
                }
                var time = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, point.first, point.second, time)
                SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
                inject(MotionEvent.ACTION_UP, point.first, point.second, time)
                instrumentation.runOnMainSync {
                    assertTrue(requireNotNull(markdownSelectionController(view)).selecting)
                    assertEquals(fraction, selectedMarkdownText(view.text, view.selectionStart, view.selectionEnd))
                }
                awaitFrame(view)
                snapshot("nested-formula-selection-$theme.png")
                var target = 0f to 0f
                instrumentation.runOnMainSync {
                    view.onTextContextMenuItem(android.R.id.copy)
                    val clipboard = view.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    assertEquals("\$\$$fraction\$\$", clipboard.primaryClip!!.getItemAt(0).text.toString())
                    val text = view.text as Spanned
                    requireNotNull(markdownSelectionController(view)).begin(text.getSpanStart(span), text.getSpanEnd(span))
                    val end = requireNotNull(selectionEndpoint(view, view.selectionEnd, false))
                    val inner = requireNotNull(selectionEndpoint(view, fractionEnd, false))
                    val location = IntArray(2).also(view::getLocationOnScreen)
                    val dx = 5f * view.resources.displayMetrics.density
                    val dy = 12f * view.resources.displayMetrics.density
                    point = location[0] + end.x + dx to location[1] + end.bottom + dy
                    target = location[0] + inner.x + dx to location[1] + inner.bottom + dy
                }
                awaitFrame(view)
                time = SystemClock.uptimeMillis()
                inject(MotionEvent.ACTION_DOWN, point.first, point.second, time)
                instrumentation.runOnMainSync {
                    assertTrue("The drag must reach the real handle popup", requireNotNull(markdownSelectionController(view)).dragging)
                }
                inject(MotionEvent.ACTION_MOVE, target.first, target.second, time)
                inject(MotionEvent.ACTION_UP, target.first, target.second, time)
                instrumentation.runOnMainSync {
                    assertEquals("A native handle must stop inside both bracket pairs", fractionEnd, view.selectionEnd)
                    val selected = selectedMarkdownText(view.text, view.selectionStart, view.selectionEnd)
                    assertTrue(selected.contains(fraction))
                    assertFalse(selected.contains("\\right]"))
                    assertFalse(selected.contains("\\right)"))
                    ru.noties.jlatexmath.JLatexMathDrawable.builder(selected).textSize(view.textSize).build()
                }
                awaitFrame(view)
                snapshot("nested-formula-drag-$theme.png")
            } finally { instrumentation.runOnMainSync { markdownSelectionController(view)?.finish() } }
        }
    }
}
