package com.moge.app.ui.document

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import com.moge.app.data.document.DocumentAttachment
import com.moge.app.data.document.DocumentIndex
import com.moge.app.data.document.DocumentSection
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.theme.MogeTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h851dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocumentPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun state(extension: String = "txt") = DocumentPreviewUiState(
        attachment = DocumentAttachment("doc", "/documents/doc.$extension",
            "通信原理 · 第三章复习笔记与例题整理.$extension", "text/plain", 238_900, "digest"),
        index = DocumentIndex((1..3).map { DocumentSection("section:$it", "第 $it 节 · 基带信号", "") }, emptyList()),
        locator = "section:1", text = "基带信号与数字传输\n\n在通信系统中，发送滤波器与接收滤波器共同决定脉冲的形状。\n\n1. 观察波形中的采样时刻。\n2. 判断是否存在码间串扰。\n3. 写出判决门限与误码概率。",
    )

    @Test @Config(qualifiers = "w393dp-h851dp-night-mdpi")
    fun `saved paper appearance overrides dark system and colors readable text`() {
        val appearance = MutableStateFlow(Appearance.PAPER)
        var chalk = true
        var bars: Boolean? = null
        var expected = UiColor.Unspecified
        compose.setContent {
            DocumentPreviewTheme(appearance, { bars = it }) {
                chalk = MogeTheme.paper.isChalk
                expected = MaterialTheme.colorScheme.onSurface
                DocumentPreviewScreen(state(), {}, {}, {}, {}, {})
            }
        }
        compose.waitUntil { !chalk && bars == false }
        assertEquals(expected, textColor())
        compose.onNodeWithText("文件预览").assertExists()
        export("document-preview-paper")
    }

    @Test
    fun `saved chalk appearance overrides light system and updates while preview is open`() {
        val appearance = MutableStateFlow(Appearance.CHALK)
        var chalk = false
        var bars: Boolean? = null
        var expected = UiColor.Unspecified
        compose.setContent {
            DocumentPreviewTheme(appearance, { bars = it }) {
                chalk = MogeTheme.paper.isChalk
                expected = MaterialTheme.colorScheme.onSurface
                DocumentPreviewScreen(state("docx"), {}, {}, {}, {}, {})
            }
        }
        compose.waitUntil { chalk && bars == true }
        assertEquals(expected, textColor())
        assertTrue("Chalk text must be light", expected.red > .8f)
        export("document-preview-chalk")
        compose.runOnIdle { appearance.value = Appearance.PAPER }
        compose.waitUntil { !chalk && bars == false }
        assertEquals(expected, textColor())
        assertTrue(expected.red < .3f)
    }

    @Test fun `page controls and validated jump replace the always visible input field`() {
        var value by mutableStateOf(state())
        compose.setContent { MogeTheme(Appearance.PAPER) {
            DocumentPreviewScreen(value, {}, {}, { position ->
                value = value.copy(selected = position, locator = "section:${position + 1}")
            }, {}, {})
        } }
        compose.onNodeWithContentDescription("上一项").assertIsNotEnabled()
        compose.onNodeWithTag("preview-jump-input").assertDoesNotExist()
        compose.onNodeWithContentDescription("下一项").performClick()
        compose.onNodeWithText("第 2 / 3 项").assertExists()
        compose.onNodeWithText("第 2 / 3 项").performClick()
        compose.onNodeWithTag("preview-jump-input").assertTextContains("2")
        export("document-preview-jump-paper", dialog = true)
        compose.onNodeWithTag("preview-jump-input").performTextReplacement("4")
        compose.onNodeWithText("前往").assertIsNotEnabled()
        compose.onNodeWithTag("preview-jump-input").performTextReplacement("")
        compose.onNodeWithText("前往").assertIsNotEnabled()
        compose.onNodeWithTag("preview-jump-input").performTextReplacement("3")
        compose.onNodeWithText("前往").performClick()
        compose.onNodeWithText("第 3 / 3 项").assertExists()
        compose.onNodeWithContentDescription("下一项").assertIsNotEnabled()
        compose.onNodeWithText("第 3 / 3 项").performClick()
        compose.onNodeWithTag("preview-jump-input").performTextReplacement("1")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("第 3 / 3 项").assertExists()
        compose.onNodeWithText("第 3 / 3 项").performClick()
        compose.onNodeWithTag("preview-jump-input").assertTextContains("3").performTextReplacement("1")
        compose.onNodeWithTag("preview-jump-input").performImeAction()
        compose.onNodeWithText("第 1 / 3 项").assertExists()
    }

    @Test fun `embedded images use readable chips and return to the selected section`() {
        val paths = listOf("word/media/image1.png", "word/media/image2.png")
        var value by mutableStateOf(state("docx").let { it.copy(index = it.index!!.copy(images = paths), selected = 1) })
        var selectedSection = -1
        compose.setContent { MogeTheme(Appearance.CHALK) {
            DocumentPreviewScreen(value, {}, {}, { selectedSection = it }, { value = value.copy(locator = it) }, {})
        } }
        compose.onNodeWithText(paths.first()).assertDoesNotExist()
        compose.onNodeWithText("图片 2").performClick()
        assertEquals(paths[1], value.locator)
        compose.onNodeWithText("返回正文").performClick()
        assertEquals(1, selectedSection)
    }

    @Test fun `loading hides stale content and error provides a usable retry`() {
        var value by mutableStateOf(state().copy(busy = true))
        var retries = 0
        compose.setContent { MogeTheme(Appearance.CHALK) {
            DocumentPreviewScreen(value, {}, {}, {}, {}, { retries++ })
        } }
        compose.onNodeWithTag("preview-body").assertDoesNotExist()
        compose.onNodeWithText("正在读取内容…").assertExists()
        compose.runOnIdle { value = value.copy(busy = false, error = "文件无法读取") }
        compose.onNodeWithText("重新加载").performClick()
        assertEquals(1, retries)
    }

    @Test fun `image pages keep their original colors inside the themed reader`() {
        val bitmap = Bitmap.createBitmap(300, 430, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        Canvas(bitmap).apply {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 18f }
            drawText("COMMUNICATION SYSTEMS", 22f, 48f, paint)
            paint.textSize = 13f
            drawText("Chapter 3 - Baseband Transmission", 22f, 82f, paint)
            paint.strokeWidth = 1f
            repeat(9) { drawLine(22f, 112f + it * 24f, 274f, 112f + it * 24f, paint) }
        }
        compose.setContent { MogeTheme(Appearance.CHALK) {
            DocumentPreviewScreen(state("pdf").copy(text = "", image = bitmap), {}, {}, {}, {}, {})
        } }
        compose.onNodeWithContentDescription("文档页面").assertExists()
        assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
        export("document-preview-image-chalk")
        // Compose may retain this bitmap in a pending display list after disposal.
    }

    private fun textColor(): UiColor {
        val result = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("preview-body").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }
        return result.single().layoutInput.style.color
    }

    private fun export(name: String, dialog: Boolean = false) {
        if (System.getenv("MOGE_EXPORT_UI_SAMPLES") != "1") return
        compose.runOnIdle {
            val views = WindowInspector.getGlobalWindowViews().filter { it.width > 0 && it.height > 0 }
            val view = if (dialog) views.last() else views.first()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                File("build/ui-preview").mkdirs()
                File("build/ui-preview/$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
}
