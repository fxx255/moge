package com.moge.app.ui.solve

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.text.Spanned
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.image.QuestionPhotoFiles
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.components.PaperHeaderStyle
import com.moge.app.ui.components.PaperNavigationLayers
import com.moge.app.ui.components.PaperScaffold
import com.moge.app.ui.export.AnswerExportContent
import com.moge.app.ui.export.AnswerExportDialog
import com.moge.app.ui.export.EXPORT_PAPER
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

@RunWith(AndroidJUnit4::class)
class MarkdownTableShareDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun snapshot(name: String, checkProcessedPhoto: Boolean = false) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                if (checkProcessedPhoto) {
                    var originalPixels = 0
                    for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                        if (bitmap.getPixel(x, y) == Color.RED) originalPixels++
                    }
                    assertTrue("The red original photo must be replaced by its processed white companion", originalPixels < 10)
                }
                File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }

    private fun longPress(x: Float, y: Float) {
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) }
            finally { event.recycle() }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 150L)
        }
    }

    private fun awaitPreviewImage() {
        compose.waitUntil(15_000) {
            val nodes = compose.onAllNodesWithTag("export-preview").fetchSemanticsNodes()
            val bounds = nodes.singleOrNull()?.boundsInRoot
            val bitmap = if (bounds != null) instrumentation.uiAutomation.takeScreenshot() else null
            if (bitmap == null || bounds == null) false else try {
                var printedPaper = 0
                for (y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height) step 8) {
                    for (x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width) step 8) {
                        if (bitmap.getPixel(x, y) == EXPORT_PAPER) printedPaper++
                    }
                }
                printedPaper > 100
            } finally { bitmap.recycle() }
        }
    }

    private fun awaitCell(predicate: (TextView) -> Boolean): TextView {
        lateinit var found: TextView
        compose.waitUntil(15_000) {
            var ready = false
            compose.runOnUiThread {
                textViews(compose.activity.window.decorView).firstOrNull {
                    it.isShown && it.isLaidOut && predicate(it)
                }?.let { found = it; ready = true }
            }
            ready
        }
        return found
    }

    @Test fun tableTextAndFormulaCopyWithoutBreakingHorizontalOrPageScrolling() {
        val theme = mutableStateOf(Appearance.PAPER)
        val snr = "\\mathrm{SNR}>2^{\\log_2 M}-1"
        val power = "P=(0.5,1,1.5)"
        val source = buildString {
            append("| 调制 | 所需 ${'$'}$snr${'$'} | (c) ${'$'}$power${'$'} | 备注 |\n|---|---|---|---|\n")
            repeat(30) { append("| PSK | ${'$'}>3${'$'} | ${'$'}$power${'$'} | 信道容量与功率分配比较 |\n") }
        }
        compose.setContent { MogeTheme(theme.value) {
            SolveList(SolveUiState(conversationId = "table-test", items = listOf(
                SolveItem.Answer("table-answer", AnswerState.COMPLETED, source))),
                {}, {}, { _, _ -> }, {}, {})
        } }
        val formulaHost = awaitCell {
            val text = it.text as? Spanned
            text?.getSpans(0, text.length, BaselineLatexSpan::class.java)?.any { span ->
                span.drawable.destination == snr && span.drawable.hasResult()
            } == true
        }
        var formulaPoint = 0f to 0f
        var expected = ""
        compose.runOnUiThread {
            val text = formulaHost.text as Spanned
            val formula = text.getSpans(0, text.length, BaselineLatexSpan::class.java).single()
            assertEquals(snr, formula.drawable.destination)
            assertFalse("A complete equation must not become display blocks", text.contains('\n'))
            val part = formulaSelectionParts(formulaHost, formula).first()
            expected = selectedMarkdownText(text, part.start, part.end, delimitFormulas = true)
            val location = IntArray(2).also(formulaHost::getLocationOnScreen)
            formulaPoint = location[0] + part.bounds.centerX() to location[1] + part.bounds.centerY()
        }
        longPress(formulaPoint.first, formulaPoint.second)
        compose.runOnUiThread {
            assertTrue(requireNotNull(markdownSelectionController(formulaHost)).selecting)
            formulaHost.onTextContextMenuItem(android.R.id.copy)
            val clipboard = formulaHost.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        // Start a swipe on actual formula/text content, not the row's spare background.
        val before = compose.onNodeWithTag("markdown-table-cell-0-2").fetchSemanticsNode().positionInRoot.x
        compose.onNodeWithTag("markdown-table-scroll").performTouchInput {
            swipe(start = androidx.compose.ui.geometry.Offset(width * 0.8f, 16f),
                end = androidx.compose.ui.geometry.Offset(width * 0.2f, 16f), durationMillis = 300)
        }
        val after = compose.onNodeWithTag("markdown-table-cell-0-2").fetchSemanticsNode().positionInRoot.x
        assertTrue("Selectable cells must still allow horizontal scrolling", after < before - 10f)
        snapshot("table-formulas-paper.png")
        compose.onNodeWithTag("markdown-table-scroll").performTouchInput { swipeRight(durationMillis = 300) }
        compose.waitForIdle()
        val wordHost = awaitCell { it.text.toString() == "PSK" }
        var point = 0f to 0f
        compose.runOnUiThread {
            val visible = android.graphics.Rect()
            assertTrue(wordHost.getGlobalVisibleRect(visible))
            val location = IntArray(2).also(wordHost::getLocationOnScreen)
            val caret = requireNotNull(selectionEndpoint(wordHost, 1, true))
            point = location[0] + caret.x to location[1] + caret.centerY
        }
        longPress(point.first, point.second)
        compose.runOnUiThread {
            assertTrue(requireNotNull(markdownSelectionController(wordHost)).selecting)
            wordHost.onTextContextMenuItem(android.R.id.copy)
            val clipboard = wordHost.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals("PSK", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
        compose.onNodeWithTag("markdown-table-scroll").performTouchInput { swipeUp(durationMillis = 300) }
        val top = compose.onNodeWithTag("markdown-table-row-0").fetchSemanticsNode().positionInRoot.y
        assertTrue("Vertical gestures inside a cell must scroll the answer", top < 0f)
        compose.runOnUiThread { theme.value = Appearance.CHALK }
        compose.waitForIdle()
        snapshot("table-scroll-chalk.png")
    }

    @Test fun sharingOwnsOpaquePaperAndBothStagesUseProcessedPhotos() {
        val context = instrumentation.targetContext
        val original = File(context.cacheDir, "share-device-original.png")
        fun photo(path: File, color: Int) {
            val bitmap = Bitmap.createBitmap(640, 160, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(color)
                val canvas = Canvas(bitmap)
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.BLACK; textSize = 28f }
                canvas.drawText("SNR > 2^(log2 M) - 1", 36f, 62f, paint)
                canvas.drawText("P = (0.5, 1, 1.5)", 36f, 116f, paint)
                path.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
        photo(original, Color.RED)
        val processed = QuestionPhotoFiles.output(original.path, requireNotNull(QuestionPhotoFiles.fingerprint(original.path)))
        photo(processed, Color.WHITE)
        val theme = mutableStateOf(Appearance.PAPER)
        val content = AnswerExportContent("信道容量与功率分配", "比较不同调制方式的信噪比与功率分配。",
            questionPhotos = listOf(original.path), answerText = "由 ${'$'}\\log_2 M${'$'} 计算频谱效率。", finalAnswer = "${'$'}P=(0.5,1,1.5)${'$'}")
        try {
            compose.setContent { MogeTheme(theme.value) {
              PaperNavigationLayers {
                PaperScaffold("不应透出的底层标题", null, headerStyle = PaperHeaderStyle.StackedRight) {
                    Text("不应透出的底层正文".repeat(50))
                }
                AnswerExportDialog(content) {}
            } } }
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("待确认的题目照片 1").fetchSemanticsNodes().isNotEmpty()
            }
            snapshot("share-review-paper.png", checkProcessedPhoto = true)
            compose.onNodeWithTag("export-confirm-question").performClick()
            compose.waitUntil(30_000) {
                compose.onAllNodesWithTag("export-preview").fetchSemanticsNodes().isNotEmpty()
            }
            awaitPreviewImage()
            compose.onNodeWithText("保存全部图片").assertIsEnabled()
            compose.onNodeWithText("系统分享").assertIsEnabled()
            snapshot("share-preview-paper.png", checkProcessedPhoto = true)
            compose.runOnUiThread { theme.value = Appearance.CHALK }
            compose.waitForIdle()
            snapshot("share-preview-chalk.png")
            compose.onNodeWithText("重新核对题目 / 导出范围").performClick()
            compose.onNodeWithTag("export-confirm-question").assertIsEnabled()
            snapshot("share-review-chalk.png")
        } finally { processed.delete(); original.delete() }
    }

    // Repeat this scenario with Android system font_scale=1.5. Dialog installs its
    // own native density, so a parent LocalDensity override cannot verify large text.
    @Test fun sharingKeepsBothStagesAndActionsVisibleInChalkTheme() {
        compose.setContent { MogeTheme(Appearance.CHALK) {
            PaperNavigationLayers {
                PaperScaffold("底层对话标题", null, headerStyle = PaperHeaderStyle.StackedRight) {
                    Text("底层正文".repeat(50))
                }
                AnswerExportDialog(AnswerExportContent("信道容量与功率分配", "比较不同调制方式的信噪比与功率分配。",
                    answerText = "计算信噪比。", finalAnswer = "${'$'}P=(0.5,1,1.5)${'$'}")) {}
            }
        } }
        compose.onNodeWithTag("export-confirm-question").assertIsDisplayed()
        snapshot("share-review-large-chalk.png")
        compose.onNodeWithTag("export-confirm-question").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("export-preview").fetchSemanticsNodes().isNotEmpty()
        }
        awaitPreviewImage()
        compose.onNodeWithText("系统分享").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("保存全部图片").assertIsDisplayed().assertIsEnabled()
        snapshot("share-preview-large-chalk.png")
    }
}
