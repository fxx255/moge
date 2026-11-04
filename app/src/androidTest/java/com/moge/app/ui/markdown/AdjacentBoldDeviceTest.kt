package com.moge.app.ui.markdown

import android.graphics.Bitmap
import android.os.SystemClock
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.export.AnswerExportContent
import com.moge.app.ui.export.ExportChoice
import com.moge.app.ui.export.exportTextView
import com.moge.app.ui.export.renderAnswerExport
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AdjacentBoldDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val boldClass = Class.forName("io.noties.markwon.core.spans.StrongEmphasisSpan")
    private val explanation = "**结构：****recommend** 后接建议的内容；**to enable members to ...** " +
        "说明该方案的作用，其中 **enable sb. to do sth.** 表示“使某人能够做某事”。"
    private val answer = "### 4. 一张借书证全国通用\n\n" +
        "> The review recommends ... the reintroduction of a scheme to enable members to use their card in any library in the country.\n\n" +
        explanation + "\n\n**翻译：**这份评估报告建议恢复一项方案，使会员能够凭借书证在全国任何一家图书馆借阅。"

    @Test fun structureAndEnglishTermsStayBoldDuringStreamingAndAfterCompletion() {
        val streaming = mutableStateOf(true)
        compose.setContent { MogeTheme(Appearance.PAPER) {
            Surface { Column(Modifier.padding(20.dp)) { StreamingMarkdownBody(answer, streaming.value) } }
        } }
        awaitAndCheckExplanation()
        compose.runOnIdle { streaming.value = false }
        awaitAndCheckExplanation()
        // Native ValueAnimator is independent of Compose's idle clock (maximum duration: 280ms).
        SystemClock.sleep(350)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val qa = File(compose.activity.filesDir, "bold-boundary-qa").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(qa, "structure-label-chat.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    @Test fun exportedImageKeepsTheStructureLabelAndEnglishTermsBold(): Unit = runBlocking {
        val context = compose.activity
        compose.runOnUiThread { assertExplanation(exportTextView(context, explanation, 1800).text) }
        val result = renderAnswerExport(context, AnswerExportContent("英语长难句解析", "分析借书证通用方案这句话的结构。", answerText = answer),
            ExportChoice.FULL, FigurePathResolver { _, _ -> null }) { }
        try {
            assertTrue(result.warnings.toString(), result.warnings.isEmpty())
            val qa = File(context.filesDir, "bold-boundary-qa").apply { mkdirs() }
            result.pages.single().copyTo(File(qa, "structure-label-export.png"), overwrite = true)
        } finally { result.files.close(force = true) }
    }

    private fun awaitAndCheckExplanation() {
        compose.waitUntil(10_000) {
            var ready = false
            compose.runOnUiThread {
                textViews(compose.activity.window.decorView).firstOrNull { it.text.contains("后接建议的内容") }?.let {
                    assertExplanation(it.text)
                    ready = true
                }
            }
            ready
        }
    }

    private fun assertExplanation(rendered: CharSequence) {
        val text = rendered as Spanned
        assertFalse(text.contains("**"))
        val ranges = text.getSpans(0, text.length, boldClass).sortedBy(text::getSpanStart).map {
            selectedMarkdownText(text, text.getSpanStart(it), text.getSpanEnd(it))
        }
        assertTrue(ranges.toString(), ranges.containsAll(listOf("结构：recommend", "to enable members to ...", "enable sb. to do sth.")))
        assertEquals(text.toString(), selectedMarkdownText(text, 0, text.length))
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
