package com.moge.app.ui.solve

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.markdown.BaselineLatexSpan
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the actual AnswerSheet state transitions and native formula first layout. */
@RunWith(AndroidJUnit4::class)
class BufferedStreamingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var text by mutableStateOf("")
    private var status by mutableStateOf(AnswerState.STREAMING)
    private var paths by mutableStateOf(emptyList<String>())
    private var theme by mutableStateOf(Appearance.PAPER)

    private fun native(view: View = compose.activity.window.decorView): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { native(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun render() {
        compose.setContent { MogeTheme(theme) {
            LazyColumn(Modifier.fillMaxSize()) { item {
                AnswerSheet(SolveItem.Answer("answer", status, text, figurePaths = paths), {}, {}, true, { _, _ -> })
            } }
        } }
        compose.waitForIdle()
    }

    private fun awaitText(part: String) {
        compose.waitUntil(10_000) {
            var found = false
            compose.runOnUiThread { found = native().any { part in it.text.toString() } }
            found
        }
        compose.waitForIdle()
    }

    @Test fun mathAndTableStayFormattedAcrossStreamingAndCompletionInBothThemes() {
        text = "由第二个分量得 "
        render()
        for (appearance in listOf(Appearance.PAPER, Appearance.CHALK)) {
            compose.runOnIdle { theme = appearance; status = AnswerState.STREAMING; text = "由第二个分量得 ${'$'}\\frac{k_2+1}{2}" }
            awaitText("由第二个分量得")
            compose.runOnIdle {
                assertFalse(native().any { "\\frac" in it.text.toString() })
                text += "${'$'}，因此 ${'$'}k_1=k_2=0${'$'}。"
            }
            awaitText("因此")
            lateinit var paragraph: TextView
            var height = 0
            compose.runOnIdle {
                paragraph = native().single()
                val math = (paragraph.text as Spanned).getSpans(0, paragraph.text.length, BaselineLatexSpan::class.java)
                assertTrue(math.isNotEmpty() && math.all { it.drawable.hasResult() })
                height = paragraph.height
            }
            snapshot("buffered-formula-${appearance.name.lowercase()}.png")
            compose.runOnIdle { text += "\n\n| 类型 | 数值 |\n| --- | --- |\n| SNR | ${'$'}\\frac{P}{N_0B}${'$'} |" }
            compose.waitForIdle()
            compose.onNodeWithTag("markdown-table").assertDoesNotExist()
            compose.runOnIdle { text += "\n| P | 较长的一项测试数据 |\n\n结论完整。" }
            awaitText("结论完整")
            val width = compose.onNodeWithTag("markdown-table-cell-0-0").fetchSemanticsNode().boundsInRoot.width
            lateinit var cell: TextView
            compose.runOnIdle {
                assertSame(paragraph, native().first())
                assertEquals(height, paragraph.height)
                cell = native().single { it.text.toString() == "SNR" }
                status = AnswerState.COMPLETED
            }
            compose.waitForIdle()
            assertEquals(width, compose.onNodeWithTag("markdown-table-cell-0-0").fetchSemanticsNode().boundsInRoot.width, .1f)
            compose.runOnIdle { assertSame(cell, native().single { it.text.toString() == "SNR" }) }
            snapshot("buffered-table-${appearance.name.lowercase()}.png")
        }
    }

    @Test fun figurePlaceholderLoadsInPlaceAndInterruptedRepliesReleaseTheirBuffer() {
        text = "图示如下：\n\n[[FIGURE:1]]\n\n这里继续输出讲解。"
        render()
        compose.onNodeWithTag("figure-pending-0").assertExists()
        snapshot("buffered-figure-pending.png")
        val file = File(compose.activity.cacheDir, "buffered-figure.png")
        val bitmap = Bitmap.createBitmap(480, 280, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(41, 70, 126); strokeWidth = 5f }
            canvas.drawLine(40f, 230f, 440f, 230f, paint)
            canvas.drawLine(40f, 230f, 40f, 30f, paint)
            canvas.drawLine(40f, 220f, 430f, 50f, paint)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
        compose.runOnIdle { paths = listOf(file.absolutePath); status = AnswerState.COMPLETED }
        compose.waitUntil(10_000) {
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("figure-pending-0").assertDoesNotExist()
        compose.onNodeWithTag("figure-slot-0").assertExists()
        snapshot("buffered-figure-ready.png")
        compose.runOnIdle {
            paths = emptyList(); status = AnswerState.STREAMING
            text = "新一轮推导\n\n[[FIGURE:1]]\n\n剩余 ${'$'}\\frac{x+1"
        }
        awaitText("剩余")
        compose.onNodeWithTag("figure-pending-0").assertExists()
        compose.runOnIdle { status = AnswerState.STOPPED }
        awaitText("\\frac{x+1")
        compose.onNodeWithTag("figure-pending-0").assertDoesNotExist()
        snapshot("buffered-stopped.png")
    }

    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
}
