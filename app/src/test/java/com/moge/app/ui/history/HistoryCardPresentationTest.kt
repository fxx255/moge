package com.moge.app.ui.history

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.HistoryEntry
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.data.prefs.Appearance
import com.moge.app.ui.photo.THUMB_MAX_PX
import com.moge.app.ui.photo.ThumbnailCache
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w480dp-h960dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HistoryCardPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val category = NotebookCategoryEntity(id = "history-category", name = "历史自定义分类")
    private val updatedAt = Instant.parse("2026-09-30T14:05:00Z")
    private val date = updatedAt.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm"))
    private val firstQuestion = "首条用户问题：求函数的导数"
    private val recentAnswer = "最近的助手解答，不能成为题目预览"
    private val state = mutableStateOf(HistoryUiState(loading = false))
    private val temporaryImages = mutableListOf<File>()
    private val notebookRequests = mutableListOf<String?>()
    private var collected = 0
    private var pinned = 0
    private var opened = 0

    @After fun cleanupImages() {
        ThumbnailCache.clear()
        temporaryImages.forEach { it.delete() }
    }

    private fun entry(id: String = "history", cover: String = "", favorites: Int = 0, isPinned: Boolean = false,
        title: String = "历史题目", preview: String = firstQuestion) = HistoryEntry(
        ConversationEntity(id = id, title = title, coverImage = cover, categoryId = category.id,
            pinned = isPinned, createdAt = updatedAt, updatedAt = updatedAt),
        recentContent = recentAnswer, requestStatus = "COMPLETED", questionPreview = preview, favoriteCount = favorites,
    )

    private fun render(vararg entries: HistoryEntry) {
        state.value = state.value.copy(entries = entries.toList(), categories = listOf(category))
        compose.setContent {
            MogeTheme(Appearance.PAPER) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1f),
                    LocalPaperMotionEnabled provides false) {
                    HistoryContent(state.value, onBack = {}, onOpenConversation = { opened++ }, onQuery = {},
                        onToggleSelection = { id -> state.value = state.value.copy(selectedIds =
                            if (id in state.value.selectedIds) state.value.selectedIds - id else state.value.selectedIds + id) },
                        onClearSelection = { state.value = state.value.copy(selectedIds = emptySet()) },
                        onSelectAll = { state.value = state.value.copy(selectedIds = state.value.entries.map { it.conversation.id }.toSet()) },
                        onRename = {}, onDelete = {}, onPin = { pinned++ }, onRetry = {}, onDismissMessage = {},
                        onCollect = { collected++ }, onOpenNotebook = { notebookRequests.add(it) })
                }
            }
        }
    }

    private fun missingCover(): String = File(ApplicationProvider.getApplicationContext<Context>().cacheDir,
        "missing-history-cover-" + UUID.randomUUID() + ".png").absolutePath

    private fun coloredCover(): String {
        val file = File(ApplicationProvider.getApplicationContext<Context>().cacheDir,
            "history-presentation-" + UUID.randomUUID() + ".png")
        temporaryImages += file
        val bitmap = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.MAGENTA)
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        // Warm the real cache so the pre-selection baseline already includes the decoded thumbnail.
        runBlocking { assertNotNull(ThumbnailCache.load(file.absolutePath, THUMB_MAX_PX)) }
        return file.absolutePath
    }

    private fun hold(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, 1f))
        }
        compose.waitForIdle()
    }

    private fun assertLiftKeepsPresentation(entry: HistoryEntry, texts: List<String>, thumbnailCount: Int) {
        val tag = "history-card-" + entry.conversation.id
        assertTrue(state.value.selectedIds.isEmpty())
        // Capture before the long press adds selection, changes the toolbar or creates a preview.
        val original = CardPresentationAssertions.capture(compose, tag, texts)
        assertEquals(thumbnailCount, original.thumbnails.size)
        hold(tag)
        assertEquals(setOf(entry.conversation.id), state.value.selectedIds)
        compose.onNodeWithTag(tag).assertIsSelected()
        compose.onNodeWithTag("drag-paper").assertExists()
        CardPresentationAssertions.assertSame(original, CardPresentationAssertions.capture(compose, tag, texts))
        CardPresentationAssertions.assertSame(original, CardPresentationAssertions.capture(compose, "drag-paper", texts))
        compose.onNodeWithText(recentAnswer, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(tag).performTouchInput { cancel() }
        compose.waitForIdle()
        compose.onNodeWithTag("drag-paper").assertDoesNotExist()
        CardPresentationAssertions.assertSame(original, CardPresentationAssertions.capture(compose, tag, texts))
        assertEquals(0, opened)
    }

    @Test fun `photo history card omits both recent answer and question preview even when cover is unavailable`() {
        render(entry(cover = missingCover()))
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("照片暂不可用", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("照片暂不可用", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(firstQuestion, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText(recentAnswer, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("历史题目", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun `text history card previews first user question rather than recent assistant content`() {
        render(entry(preview = "\n" + firstQuestion + "\n后面的题目说明"))
        compose.onNodeWithText(firstQuestion, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(recentAnswer, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("后面的题目说明", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("题目照片", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("照片暂不可用", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `missing question preview never falls back to the latest answer`() {
        render(entry(preview = ""))
        compose.onNodeWithText("历史题目", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(recentAnswer, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun `history header notebook icon opens notebook without targeting a favorite`() {
        render(entry())
        compose.onNodeWithContentDescription("我的题册").assertIsEnabled().performClick()
        assertEquals(listOf<String?>(null), notebookRequests)
        assertEquals(0, collected)
        assertEquals(0, pinned)
        assertEquals(0, opened)
    }

    @Test fun `selection bookmark collects and push pin invokes a separate callback`() {
        render(entry(favorites = 2))
        compose.onNodeWithTag("history-card-history").performSemanticsAction(SemanticsActions.OnLongClick) { it() }
        compose.onNodeWithText("已选 1 个").assertExists()
        compose.onNodeWithContentDescription("收藏最新解答到题册").assertIsEnabled().performClick()
        assertEquals(1, collected)
        assertEquals(0, pinned)
        compose.onNodeWithContentDescription("置顶或取消置顶").assertIsEnabled().performClick()
        assertEquals(1, collected)
        assertEquals(1, pinned)
        assertTrue(notebookRequests.isEmpty())
        assertEquals(0, opened)
    }

    @Test fun `bookmark remains available for multiple selected conversations while push pin is disabled`() {
        render(entry(), entry(id = "second", title = "另一道题"))
        compose.runOnIdle { state.value = state.value.copy(selectedIds = setOf("history", "second")) }
        compose.onNodeWithContentDescription("收藏最新解答到题册").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("置顶或取消置顶").assertIsNotEnabled()
        assertEquals(1, collected)
        assertEquals(0, pinned)
    }

    @Test fun `favorite badge shows the count independently of pin state and disappears at zero`() {
        render(entry(favorites = 3))
        compose.onNodeWithText("已收藏 3 条解答", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("已置顶", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(entries = listOf(entry(isPinned = true))) }
        compose.onNodeWithText("已收藏 3 条解答", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("已收藏 0 条解答", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("已置顶", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun `text card keeps original size title question category date and badge through selection and lift`() {
        val entry = entry(favorites = 2, isPinned = true, title = "需要换行的原始历史题目标题保留完整排版")
        render(entry)
        assertLiftKeepsPresentation(entry, listOf(entry.conversation.title, firstQuestion, category.name, date,
            "已收藏 2 条解答", "已置顶"), thumbnailCount = 0)
    }

    @Test fun `live history updates cannot rewrite the lifted card snapshot`() {
        val original = entry(favorites = 1)
        render(original)
        val texts = listOf(original.conversation.title, firstQuestion, category.name, date, "已收藏 1 条解答")
        val baseline = CardPresentationAssertions.capture(compose, "history-card-history", texts)
        hold("history-card-history")
        compose.runOnIdle {
            state.value = state.value.copy(entries = listOf(original.copy(
                conversation = original.conversation.copy(title = "更新后的题目"),
                questionPreview = "更新后的提问", favoriteCount = 2)))
        }
        compose.onNode(hasText("更新后的提问") and hasAnyAncestor(hasTestTag("history-card-history")),
            useUnmergedTree = true).assertIsDisplayed()
        CardPresentationAssertions.assertSame(baseline, CardPresentationAssertions.capture(compose, "drag-paper", texts))
        compose.onNodeWithTag("history-card-history").performTouchInput { cancel() }
    }

    @Test fun `photo card lift preserves original thumbnail title category date and badge without adding excerpts`() {
        val entry = entry(cover = coloredCover(), favorites = 1, title = "带照片的原始历史题目")
        render(entry)
        compose.onNodeWithContentDescription("题目照片", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(firstQuestion, useUnmergedTree = true).assertDoesNotExist()
        assertLiftKeepsPresentation(entry, listOf(entry.conversation.title, category.name, date,
            "已收藏 1 条解答"), thumbnailCount = 1)
        compose.onNodeWithText(firstQuestion, useUnmergedTree = true).assertDoesNotExist()
    }
}

/** Compare the actual screen cards' content/layout; generic drag geometry is covered in PaperDrag tests. */
internal object CardPresentationAssertions {
    internal data class TextPresentation(val bounds: Rect, val layout: TextLayoutResult)
    internal data class Snapshot(val bounds: Rect, val texts: Map<String, TextPresentation>, val thumbnails: List<Rect>)

    fun capture(compose: ComposeContentTestRule, tag: String, texts: List<String>): Snapshot {
        val card = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val content = texts.associateWith { text ->
            val node = compose.onNode(hasText(text, substring = false) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .assertIsDisplayed()
            val bounds = relative(node.fetchSemanticsNode().boundsInRoot, card)
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get -> assertTrue(get(layouts)) }
            TextPresentation(bounds, layouts.single())
        }
        val thumbnails = compose.onAllNodes(hasContentDescription("题目照片") and hasAnyAncestor(hasTestTag(tag)),
            useUnmergedTree = true).fetchSemanticsNodes().map { relative(it.boundsInRoot, card) }
        return Snapshot(card, content, thumbnails)
    }

    fun assertSame(before: Snapshot, after: Snapshot) {
        assertEquals("Card width changed", before.bounds.width, after.bounds.width, 0.5f)
        assertEquals("Card height changed", before.bounds.height, after.bounds.height, 0.5f)
        assertEquals(before.texts.keys, after.texts.keys)
        before.texts.forEach { (text, original) ->
            val current = after.texts.getValue(text)
            assertRect(text, original.bounds, current.bounds)
            assertEquals(text, original.layout.layoutInput.text, current.layout.layoutInput.text)
            assertEquals(text, original.layout.layoutInput.style.fontSize, current.layout.layoutInput.style.fontSize)
            assertEquals(text, original.layout.layoutInput.style.fontWeight, current.layout.layoutInput.style.fontWeight)
            assertEquals(text, original.layout.layoutInput.style.lineHeight, current.layout.layoutInput.style.lineHeight)
            assertEquals(text, original.layout.lineCount, current.layout.lineCount)
            for (line in 0 until original.layout.lineCount) {
                assertEquals(text, original.layout.getLineStart(line), current.layout.getLineStart(line))
                assertEquals(text, original.layout.getLineEnd(line, visibleEnd = true), current.layout.getLineEnd(line, visibleEnd = true))
                assertEquals(text, original.layout.isLineEllipsized(line), current.layout.isLineEllipsized(line))
                assertEquals(text, original.layout.getLineTop(line), current.layout.getLineTop(line), 0.5f)
                assertEquals(text, original.layout.getLineBottom(line), current.layout.getLineBottom(line), 0.5f)
                assertEquals(text, original.layout.getLineLeft(line), current.layout.getLineLeft(line), 0.5f)
                assertEquals(text, original.layout.getLineRight(line), current.layout.getLineRight(line), 0.5f)
            }
        }
        assertEquals("Thumbnail count changed", before.thumbnails.size, after.thumbnails.size)
        before.thumbnails.zip(after.thumbnails).forEach { (original, current) -> assertRect("Thumbnail", original, current) }
    }

    private fun relative(bounds: Rect, card: Rect) = Rect(bounds.left - card.left, bounds.top - card.top,
        bounds.right - card.left, bounds.bottom - card.top)
    private fun assertRect(label: String, before: Rect, after: Rect) {
        assertEquals(label + " left changed", before.left, after.left, 0.5f)
        assertEquals(label + " top changed", before.top, after.top, 0.5f)
        assertEquals(label + " right changed", before.right, after.right, 0.5f)
        assertEquals(label + " bottom changed", before.bottom, after.bottom, 0.5f)
    }
}
