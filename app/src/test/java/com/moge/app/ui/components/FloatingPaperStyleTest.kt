package com.moge.app.ui.components

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.HistoryEntry
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.db.NotebookEntryEntity
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.history.HistoryContent
import com.moge.app.ui.history.HistoryUiState
import com.moge.app.ui.notebook.NotebookContent
import com.moge.app.ui.notebook.NotebookUiState
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h820dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingPaperStyleTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `paper history tape scrolls with the card below the floating search`() = verify(Appearance.PAPER, false)
    @Test fun `chalk history tape scrolls with the card below the floating search`() = verify(Appearance.CHALK, false)
    @Test fun `paper notebook tape scrolls with the card below the floating search`() = verify(Appearance.PAPER, true)
    @Test fun `chalk notebook tape scrolls with the card below the floating search`() = verify(Appearance.CHALK, true)
    @Test fun `large chalk text keeps tape clear of card content`() = verify(Appearance.CHALK, false, 1.8f)

    private fun verify(appearance: Appearance, notebook: Boolean, fontScale: Float = 1f) {
        val categories = listOf(NotebookCategoryEntity(id = "communication", name = "通信原理"),
            NotebookCategoryEntity(id = "math", name = "高数"), NotebookCategoryEntity(id = "english", name = "英语"))
        val date = Instant.parse("2026-10-15T03:08:00Z")
        val titles = listOf("编码与量化误差计算", "化工萃取分离板坐标查错", "含奇点曲线积分中格林公式的适用条件",
            "64QAM 调制与解调框图订正", "升余弦功率谱与带宽", "二重积分的换元方法")
        val preview = "均匀量化器的量化间隔与信噪比，逐步核对计算。"
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalPaperMotionEnabled provides false) {
                MogeTheme(appearance) {
                    if (notebook) NotebookContent(
                        NotebookUiState(loading = false, categories = categories, entries = titles.mapIndexed { i, title ->
                            NotebookEntryEntity(id = "item$i", sourceConversationId = "source", sourceQuestionId = "q$i",
                                sourceAnswerId = "a$i", title = title, questionText = preview, answerText = "保存的解答",
                                categoryId = "communication", updatedAt = date)
                        }), onBack = {}, onOpenConversation = {}, onQuery = {}, onCategory = { _, _ -> },
                        onToggleSelection = {}, onClearSelection = {}, onSelectAll = {}, onOpenEntry = {}, onCloseEntry = {},
                        onMove = {}, onDelete = {}, onCreateCategory = {}, onRenameCategory = { _, _ -> },
                        onReorderCategory = { _, _ -> }, onDeleteCategory = {}, onRetry = {}, onDismissMessage = {},
                    ) else HistoryContent(
                        HistoryUiState(loading = false, categories = categories, entries = titles.mapIndexed { i, title ->
                            HistoryEntry(ConversationEntity(id = "item$i", title = title, categoryId = "communication", updatedAt = date),
                                recentContent = "解答", questionPreview = preview, favoriteCount = 1)
                        }), onBack = {}, onOpenConversation = {}, onQuery = {}, onToggleSelection = {},
                        onClearSelection = {}, onSelectAll = {}, onRename = {}, onDelete = {}, onPin = {},
                        onRetry = {}, onDismissMessage = {},
                    )
                }
            }
        }
        val page = if (notebook) "notebook" else "history"
        val cardTag = "$page-card-item0"
        val card = compose.onNodeWithTag(cardTag)
        val tape = compose.onNode(hasTestTag("paper-card-tape") and hasAnyAncestor(hasTestTag(cardTag)), useUnmergedTree = true)
        val filters = compose.onNodeWithTag("$page-category-filters")
        val cardBefore = card.fetchSemanticsNode().boundsInRoot
        val tapeBefore = tape.fetchSemanticsNode().boundsInRoot
        val filterBefore = filters.fetchSemanticsNode().boundsInRoot
        val firstText = compose.onNode(hasText(if (notebook) titles.first() else preview) and hasAnyAncestor(hasTestTag(cardTag)),
            useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Tape must fit inside the item", cardBefore.contains(tapeBefore.topLeft) && cardBefore.contains(tapeBefore.bottomRight))
        assertTrue("Tape must not cover text", tapeBefore.bottom < firstText.top)
        export(view, "$page-${appearance.name.lowercase()}${if (fontScale > 1f) "-large" else ""}")
        val list = compose.onNode(hasScrollAction() and hasAnyDescendant(hasTestTag(cardTag)))
        list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 90f) }
        compose.waitForIdle()
        val cardAfter = card.fetchSemanticsNode().boundsInRoot
        val tapeAfter = tape.fetchSemanticsNode().boundsInRoot
        assertTrue("Cards must scroll", cardAfter.top < cardBefore.top - 40f)
        assertEquals("Tape must travel with its card", cardAfter.top - cardBefore.top, tapeAfter.top - tapeBefore.top, 1f)
        assertEquals("Filters must stay above the scrolling cards", filterBefore, filters.fetchSemanticsNode().boundsInRoot)
        export(view, "$page-${appearance.name.lowercase()}${if (fontScale > 1f) "-large" else ""}-scrolled")
    }

    private fun export(view: View, name: String) {
        if (System.getenv("PAPER_SEARCH_EXPORT_SAMPLES") != "1") return
        compose.runOnIdle {
            val root = view.rootView
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val file = File("build/paper-search-samples/$name.png")
                file.parentFile?.mkdirs()
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
}
