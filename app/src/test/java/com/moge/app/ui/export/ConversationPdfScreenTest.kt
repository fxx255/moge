package com.moge.app.ui.export

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.prefs.Appearance
import com.moge.app.domain.*
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationPdfScreenTest {
    @get:Rule val compose = createComposeRule()
    private val state = mutableStateOf(ConversationPdfUiState(loading = false,
        categories = listOf(NotebookCategoryEntity(id = "signal", name = "通信原理"), NotebookCategoryEntity(id = "english", name = "英语写作")),
        items = listOf(
            ConversationExportItem("one", "傅里叶变换与频谱分析", "周期信号的频谱为什么是离散的？", "signal", Instant.parse("2026-11-16T09:00:00Z")),
            ConversationExportItem("two", "卷积码的网格图", "根据生成多项式绘制网格图，并标注输出。", "signal", Instant.parse("2026-11-15T09:00:00Z")),
            ConversationExportItem("three", "英语作文 · 结尾优化", "如何让这段结尾更自然？", "english", Instant.parse("2026-11-13T09:00:00Z")),
        ), selectedIds = setOf("one", "three")))
    private var exported = 0
    private var view: View? = null
    private fun render(chalk: Boolean = false, scale: Int = 100, appearance: State<Appearance>? = null) {
        compose.setContent { MogeTheme(appearance?.value ?: if (chalk) Appearance.CHALK else Appearance.PAPER,
            uiScalePercent = scale, bodyTextSizeSp = if (scale > 100) 24 else 18) {
            val current = LocalView.current
            SideEffect { view = current }
            ConversationPdfContent(state.value, {}, { state.value = state.value.copy(filter = it) }, { id ->
                state.value = state.value.copy(selectedIds = if (id in state.value.selectedIds) state.value.selectedIds - id else state.value.selectedIds + id)
            }, { state.value = state.value.copy(selectedIds = state.value.items.map { it.id }.toSet()) }, {
                state.value = state.value.copy(selectedIds = state.value.selectedIds + filterConversationExports(state.value.items, state.value.filter).map { it.id })
            }, { state.value = state.value.copy(selectedIds = emptySet()) }, { exported++ }, {}, {})
        } }
    }

    @Test fun `subject selection preserves hidden choices and time order changes list`() {
        render()
        compose.onNodeWithText("全部科目").performClick()
        compose.onNodeWithText("通信原理").performClick()
        compose.onNodeWithTag("pdf-item-three").assertDoesNotExist()
        compose.onNodeWithText("选中筛选结果").performClick()
        assertEquals(setOf("one", "two", "three"), state.value.selectedIds)
        compose.onNodeWithContentDescription("时间排序").performClick()
        compose.onNodeWithText("由旧到新").performClick()
        assertEquals(ConversationExportOrder.OLDEST_FIRST, state.value.filter.order)
        compose.onNodeWithTag("pdf-export-share").performClick()
        assertEquals(1, exported)
    }

    @Test fun `large chalk interface keeps export reachable and list scrolls past controls`() {
        render(chalk = true, scale = 150)
        compose.onNodeWithTag("pdf-export-share").assertIsDisplayed().assertHeightIsAtLeast(androidx.compose.ui.unit.Dp(48f))
        compose.onNodeWithTag("pdf-selection-list").performScrollToNode(hasTestTag("pdf-item-three"))
        compose.onNodeWithTag("pdf-item-three").assertIsDisplayed().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("pdf-selection-count").assertIsDisplayed()
    }

    @Test fun `render paper and chalk selection previews`() {
        if (System.getenv("PDF_EXPORT_SAMPLES") != "1") return
        val appearance = mutableStateOf(Appearance.PAPER)
        render(appearance = appearance)
        for (theme in listOf(Appearance.PAPER, Appearance.CHALK)) {
            compose.runOnIdle { appearance.value = theme }
            compose.waitForIdle()
            compose.runOnIdle {
                val root = requireNotNull(view).rootView
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    val output = File("build/conversation-pdf-samples/${theme.name.lowercase()}-selection.png")
                    output.parentFile!!.mkdirs()
                    output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
    }
}
