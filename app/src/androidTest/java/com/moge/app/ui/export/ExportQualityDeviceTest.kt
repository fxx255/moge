package com.moge.app.ui.export

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.net.Uri
import android.content.Intent
import android.text.Spanned
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.ui.markdown.FigurePathResolver
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.viewer.imageShareIntent
import io.noties.markwon.image.AsyncDrawableSpan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportQualityDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun questionConfirmationPreviewUsesRenderedMath() {
        val question = "我怎么算的\$\$\\lambda=-2\$\$时，xy分别为2和-1啊"
        compose.setContent { MogeTheme {
            AnswerExportDialog(AnswerExportContent("追问公式预览", question, answerText = "解答"), onDismiss = {})
        } }
        // AndroidView's TextView is native; inspect it with Espresso rather than Compose semantics.
        compose.waitUntil(10_000) {
            var ready = false
            try {
                onView(withContentDescription(question)).check { view, error ->
                    if (error != null) throw error
                    val text = (view as TextView).text
                    ready = text is Spanned && text.getSpans(0, text.length, AsyncDrawableSpan::class.java)
                        .any { it.drawable.hasResult() } && !text.contains('$')
                }
            } catch (_: NoMatchingViewException) { }
            ready
        }
        onView(withContentDescription(question)).check(matches(isDisplayed()))
        val qa = File(compose.activity.filesDir, "export-quality-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(qa, "follow-up-math-confirmation.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun followUpQuestionMathRendersAndSharingReadsTheOriginalPng(): Unit = runBlocking {
        val context = compose.activity
        val question = "我怎么算的\$\$\\lambda=-2\$\$时，xy分别为2和-1啊"
        val content = AnswerExportContent("追问公式导出", question,
            questionTranscript = "已知 \\(x+y=1\\)，求 \$x^2+y^2+xy\$。",
            answerText = "将 \\(\\lambda=-2\\) 代入方程，检查 \\(x=2,y=-1\\) 是否满足条件。")
        compose.runOnUiThread {
            com.moge.app.core.LatexEngine.init(context)
            val part = exportParts(content, ExportChoice.FULL).filterIsInstance<ExportPart.Text>().single { it.source == question }
            val view = exportTextView(context, part.source, ExportLimits.CONTENT_WIDTH, markdown = part.markdown)
            val text = view.text as Spanned
            val formula = text.getSpans(0, text.length, AsyncDrawableSpan::class.java).single()
            assertEquals("\\lambda=-2", formula.drawable.destination)
            assertTrue(formula.drawable.hasResult())
            assertFalse(text.contains('$'))
        }
        val result = renderAnswerExport(context, content, ExportChoice.FULL, FigurePathResolver { _, _ -> null }) { }
        try {
            assertTrue("Unexpected export warnings: ${result.warnings}", result.warnings.isEmpty())
            val page = result.pages.single()
            val intent = imageShareIntent(context, listOf(page.path))
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
            val shared = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            assertArrayEquals("The share URI must expose the original PNG bytes", page.readBytes(), shared)
            val qa = File(context.filesDir, "export-quality-qa").apply { mkdirs() }
            page.copyTo(File(qa, "follow-up-math-export.png"), overwrite = true)
        } finally { result.files.close(force = true) }
    }

    @Test fun longPngRegionsKeepPixelsAndPreviewScrollsAndZooms(): Unit = runBlocking {
        val context = compose.activity
        val files = ExportFiles.create(context)
        try {
            val paint = Paint()
            val page = files.writePage(2160, 24000, 1) { canvas, top, bottom ->
                canvas.drawColor(Color.WHITE)
                for (y in top until bottom) {
                    paint.color = Color.rgb(y % 256, (y * 3) % 256, (y * 7) % 256)
                    canvas.drawRect(0f, y.toFloat(), 2160f, (y + 1).toFloat(), paint)
                }
            }
            val tile = exportPreviewTiles(24000)[12]
            val bitmap = decodeExportPreviewTile(page.path, 2160, tile, 1500)!!
            try {
                assertEquals(2160, bitmap.width)
                for (relativeY in listOf(0, 511, 512, 1023)) {
                    val y = tile.top + relativeY
                    assertEquals(Color.rgb(y % 256, (y * 3) % 256, (y * 7) % 256), bitmap.getPixel(100, relativeY))
                }
            } finally { bitmap.recycle() }
            val lastTile = exportPreviewTiles(24000).last()
            val lastBitmap = decodeExportPreviewTile(page.path, 2160, lastTile, 1500)!!
            try {
                val y = 23999
                assertEquals(Color.rgb(y % 256, (y * 3) % 256, (y * 7) % 256),
                    lastBitmap.getPixel(100, y - lastTile.top))
            } finally { lastBitmap.recycle() }
            compose.setContent { MogeTheme {
                ExportLongImagePreview(page.path, "高清长图", Modifier.size(300.dp, 500.dp))
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("高清长图").fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(exportPreviewTiles(24000).lastIndex)
            val before = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().layoutInfo.width
            compose.onNodeWithTag("export-preview").performTouchInput { doubleClick() }
            val after = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().layoutInfo.width
            assertTrue("Double tap should enlarge the page", after > before * 1.9f)
        } finally { files.close(force = true) }
    }

    @Test fun nativeTextFormulasAndTableExportAtDoubleResolution() = runBlocking {
        val context = compose.activity
        val qa = File(context.filesDir, "export-quality-qa").apply { mkdirs() }
        val body = (1..60).joinToString("\n\n") { index ->
            "第 $index 步：利用积分公式计算并检查边界条件。\$\\frac{x^2}{2}+C\$"
        } + "\n\n| 项目 | 结果 |\n|---|---|\n| 积分 | \$\\frac{1}{3}\$ |"
        val result = renderAnswerExport(context,
            AnswerExportContent("积分推导与验证", "计算定积分并说明每一步。",
                answerText = body, finalAnswer = "\$\\int_0^1 x^2 dx=\\frac{1}{3}\$"),
            ExportChoice.FULL, FigurePathResolver { _, _ -> null }) { }
        try {
            val page = result.pages.first()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.path, bounds)
            assertEquals(2160, bounds.outWidth)
            assertTrue("Expected a long page, got ${bounds.outHeight}px", bounds.outHeight > 6000)
            assertTrue("Unexpected export warnings: ${result.warnings}", result.warnings.isEmpty())
            page.copyTo(File(qa, "high-resolution-export.png"), overwrite = true)
            compose.setContent { MogeTheme {
                ExportLongImagePreview(page.path, "高清文字预览", Modifier.size(360.dp, 600.dp))
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("高清文字预览").fetchSemanticsNodes().isNotEmpty() }
            Thread.sleep(750)
            fun capture(name: String) {
                val bitmap = compose.onNodeWithTag("export-preview").captureToImage().asAndroidBitmap()
                File(qa, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            capture("preview-normal.png")
            compose.onNodeWithTag("export-preview").performTouchInput { doubleClick() }
            Thread.sleep(750)
            capture("preview-zoomed.png")
        } finally { result.files.close(force = true) }
    }
}
