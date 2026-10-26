package com.moge.app.ui.solve

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.text.Spanned
import android.view.View
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
import com.moge.app.ui.markdown.markdownSelectionPath
import com.moge.app.ui.markdown.selectionEndpoint
import com.moge.app.ui.markdown.selectedMarkdownText
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FormulaSelectionAlignmentDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun views(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
        else -> emptyList()
    }

    @Test fun singleMathLetterAndChineseShareTheirHighlightHeightInTheMagnifier() {
        val theme = mutableStateOf(Appearance.PAPER)
        val source = "交点是 ${'$'}${'$'}\\left(\\frac{\\sqrt{2}}{2},\\frac{\\sqrt{2}}{2}\\right)${'$'}${'$'}，" +
            "圆与 ${'$'}${'$'}x${'$'}${'$'} 轴的右交点是 ${'$'}${'$'}(\\sqrt{2},0)${'$'}${'$'}。"
        compose.setContent { MogeTheme(theme.value) {
            SolveList(SolveUiState(conversationId = "magnifier-height", items = listOf(
                SolveItem.Answer("answer", AnswerState.COMPLETED, source))), {}, {}, { _, _ -> }, {}, {})
        } }
        for (appearance in listOf(Appearance.PAPER, Appearance.CHALK)) {
            compose.runOnUiThread { theme.value = appearance }
            compose.waitForIdle()
            lateinit var body: TextView
            compose.waitUntil(15_000) {
                var ready = false
                compose.runOnUiThread {
                    views(compose.activity.window.decorView).forEach { view ->
                        val text = view.text as? Spanned
                        val formulas = text?.getSpans(0, text.length, BaselineLatexSpan::class.java).orEmpty()
                        if (view.isLaidOut && view.isShown && formulas.size == 3 && formulas.all { it.drawable.hasResult() }) {
                            body = view; ready = true
                        }
                    }
                }
                ready
            }
            compose.runOnUiThread {
                val text = body.text as Spanned
                val letter = text.getSpans(0, text.length, BaselineLatexSpan::class.java)
                    .single { it.drawable.destination == "x" }
                val from = text.getSpanStart(letter)
                val to = text.toString().indexOf("右交") + 2
                val controller = requireNotNull(markdownSelectionController(body))
                controller.begin(from, to)
                val line = body.layout.getLineForOffset(from)
                val band = RectF().also { bounds ->
                    Path().also { markdownSelectionPath(body, from, minOf(to, body.layout.getLineEnd(line)), it) }
                        .computeBounds(bounds, true)
                }
                val caret = requireNotNull(selectionEndpoint(body, from, true))
                val location = IntArray(2).also(body::getLocationOnScreen)
                controller.beginDrag(true, location[0] + caret.x, location[1] + caret.bottom)
                assertTrue(controller.magnifier.isShowing)
                assertEquals(band.centerY(), controller.magnifier.sourcePoint.second, 1f)
            }
            // Active handle dragging keeps the Compose scroll loop requesting
            // frames until release; wait for the native popup without asking
            // Espresso to make that intentional animation idle.
            try {
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    try { File(instrumentation.targetContext.getExternalFilesDir(null), "math-letter-cjk-magnifier-$appearance.png")
                        .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    finally { bitmap.recycle() }
                }
            } finally {
                instrumentation.runOnMainSync {
                    markdownSelectionController(body)?.endDrag()
                    markdownSelectionController(body)?.finish()
                }
            }
        }
    }

    @Test fun realFormulaPixelsHighlightsAndCopiedItemsAgreeInBothThemes() {
        val theme = mutableStateOf(Appearance.PAPER)
        val equations = listOf("A'=[\\alpha_1,\\alpha_3,\\alpha_5]", "B'=[\\beta_1,\\beta_3,\\beta_5]",
            "c_1\\alpha_1+c_3\\alpha_3+c_5\\alpha_5=0")
        compose.setContent { MogeTheme(theme.value) {
            SolveList(SolveUiState(conversationId = "alignment", items = listOf(
                SolveItem.Answer("answer", AnswerState.COMPLETED, equations.joinToString("\n\n") { "\$\$\n$it\n\$\$" }))),
                {}, {}, { _, _ -> }, {}, {})
        } }
        for (appearance in listOf(Appearance.PAPER, Appearance.CHALK)) {
            compose.runOnUiThread { theme.value = appearance }
            // The native view is recreated when the theme ink changes. Wait
            // for that composition before looking for a ready drawable.
            compose.waitForIdle()
            lateinit var view: TextView
            compose.waitUntil(15_000) {
                var ready = false
                compose.runOnUiThread {
                    views(compose.activity.window.decorView).forEach { candidate ->
                        val text = candidate.text as? Spanned
                        val spans = text?.getSpans(0, text.length, BaselineLatexSpan::class.java).orEmpty()
                        if (candidate.isLaidOut && candidate.isShown && spans.size == 3 && spans.all { it.drawable.hasResult() }) {
                            view = candidate; ready = true
                        }
                    }
                }
                ready
            }
            compose.runOnUiThread {
                val text = view.text as Spanned
                val spans = text.getSpans(0, text.length, BaselineLatexSpan::class.java).sortedBy { text.getSpanStart(it) }
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                try {
                    view.draw(Canvas(bitmap))
                    spans.forEach { span ->
                        val path = Path().also { markdownSelectionPath(view, text.getSpanStart(span), text.getSpanEnd(span), it) }
                        val bounds = RectF().also { path.computeBounds(it, true) }
                        val region = Region().apply { setPath(path, Region(0, 0, view.width, view.height)) }
                        val line = view.layout.getLineForOffset(text.getSpanStart(span))
                        val ink = mutableListOf<Pair<Int, Int>>()
                        val top = view.totalPaddingTop + view.layout.getLineTop(line)
                        val bottom = (view.totalPaddingTop + view.layout.getLineBottom(line)).coerceAtMost(view.height)
                        for (y in top until bottom) for (x in 0 until view.width) {
                            val pixel = bitmap.getPixel(x, y)
                            val visible = Color.alpha(pixel) > 128 && if (appearance == Appearance.PAPER)
                                Color.red(pixel) < 80 else Color.red(pixel) > 180
                            if (visible) ink += x to y
                        }
                        assertTrue("No visible formula ink for $appearance: ${span.drawable.destination}", ink.isNotEmpty())
                        assertEquals(ink.minOf { it.first }.toFloat(), bounds.left, view.textSize * .4f)
                        assertEquals(ink.maxOf { it.first }.toFloat() + 1f, bounds.right, view.textSize * .4f)
                        assertTrue(ink.count { (x, y) -> !region.contains(x, y) } < ink.size * .025f + 3)
                    }
                } finally { bitmap.recycle() }
                val part = formulaSelectionParts(view, spans.first()).single {
                    selectedMarkdownText(text, it.start, it.end) == "\\alpha_3"
                }
                val start = view.getOffsetForPosition(part.bounds.left + 1, part.bounds.centerY())
                val end = view.getOffsetForPosition(part.bounds.right - 1, part.bounds.centerY())
                assertEquals("\\alpha_3", selectedMarkdownText(text, start, end))
                requireNotNull(markdownSelectionController(view)).begin(0, text.length)
                assertTrue(view.hasSelection())
            }
            compose.waitForIdle()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { File(instrumentation.targetContext.getExternalFilesDir(null), "short-formula-alignment-$appearance.png")
                    .outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
            compose.runOnUiThread { markdownSelectionController(view)?.finish() }
        }
    }
}
