package com.moge.app.ui.components

import android.app.Application
import org.robolectric.annotation.GraphicsMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.data.db.*
import com.moge.app.ui.history.HistoryContent
import com.moge.app.ui.history.HistoryUiState
import com.moge.app.ui.notebook.NotebookContent
import com.moge.app.ui.notebook.NotebookUiState
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperDragInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val category = NotebookCategoryEntity(id = "custom", name = "我的分组")
    private val history = mutableStateOf(HistoryUiState(loading = false, categories = listOf(category),
        entries = listOf(HistoryEntry(ConversationEntity(id = "a", title = "会话 A"), "题目 A"),
            HistoryEntry(ConversationEntity(id = "b", title = "会话 B"), "题目 B"))))
    private val notebook = mutableStateOf(NotebookUiState(loading = false, categories = listOf(category),
        entries = listOf(NotebookEntryEntity(id = "a", sourceConversationId = "source", sourceQuestionId = "q", sourceAnswerId = "r",
            title = "收藏 A", questionText = "题目", answerText = "解答"))))
    private var opened = 0
    private var droppedIds: Set<String> = emptySet()
    private var movedTo: String? = null
    private var deleted = false
    private val previewText = mutableStateOf("原始正文\n保留第二行")
    private val cardSelection = mutableStateOf(emptySet<String>())

    @Composable
    private fun PreviewCard(body: String, modifier: Modifier = Modifier) {
        Surface(modifier) {
            Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("原始标题")
                Text(body)
                Image(ColorPainter(Color.Magenta), "原有缩略图", Modifier.size(40.dp).testTag("card-thumbnail"))
                Text("2026-10-03")
                Text("完整标签", Modifier.testTag("card-metadata"))
            }
        }
    }

    private fun renderCard(width: Dp = 120.dp, height: Dp = 250.dp, sourceViewportHeight: Dp? = null): PaperDragState {
        val drag = PaperDragState()
        compose.setContent { MogeTheme { CompositionLocalProvider(LocalPaperMotionEnabled provides true) {
            val body = previewText.value
            val preview: @Composable () -> Unit = remember(body) { @Composable { PreviewCard(body) } }
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.align(Alignment.Center).width(width).height(sourceViewportHeight ?: height)
                    .then(if (sourceViewportHeight != null) Modifier.clipToBounds() else Modifier)) {
                    PreviewCard(body, Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(width, height)
                        .testTag("component-card").paperDragSource(drag, "component", "原始标题", cardSelection.value, true,
                            { cardSelection.value = cardSelection.value + it },
                            { droppedIds = it.ids; movedTo = (it.target as? PaperDropTarget.Category)?.id }, preview))
                }
                PaperDragOverlay(drag, history.value.categories, Modifier.matchParentSize())
            }
        } } }
        return drag
    }
    private fun renderHistory() {
        compose.setContent { MogeTheme {
            HistoryContent(history.value, {}, { opened++ }, {},
                { id -> history.value = history.value.copy(selectedIds = if (id in history.value.selectedIds) history.value.selectedIds - id else history.value.selectedIds + id) },
                { history.value = history.value.copy(selectedIds = emptySet()) }, {}, {}, {}, {}, {}, {},
                onMoveItems = { ids, group -> droppedIds = ids; movedTo = group },
                onDeleteItems = { droppedIds = it; deleted = true })
        } }
    }
    private fun renderNotebook() {
        compose.setContent { MogeTheme {
            NotebookContent(notebook.value, {}, {}, {}, { _, _ -> },
                { id -> notebook.value = notebook.value.copy(selectedIds = if (id in notebook.value.selectedIds) notebook.value.selectedIds - id else notebook.value.selectedIds + id) },
                {}, {}, { opened++ }, {}, {}, {}, {}, { _, _ -> }, { _, _ -> }, {}, {}, {},
                onMoveItems = { ids, group -> droppedIds = ids; movedTo = group },
                onDeleteItems = { droppedIds = it; deleted = true })
        } }
    }

    private fun hold(tag: String) {
        compose.onNodeWithTag(tag).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, 1f))
        }
        compose.waitForIdle()
    }
    private fun moveTo(tag: String, target: String) {
        val destination = compose.onNodeWithTag(target).fetchSemanticsNode().boundsInRoot.center
        val origin = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag(tag).performTouchInput { moveTo(destination - origin, delayMillis = 240) }
        compose.waitForIdle()
    }
    private fun unfold(tag: String) {
        moveTo(tag, "drop-category-prompt")
        compose.onNodeWithTag("drop-category-grid").assertIsDisplayed()
    }
    private fun dragTo(tag: String, target: String) {
        if (target.startsWith("drop-category-") && target != "drop-category-prompt") unfold(tag)
        val destination = compose.onNodeWithTag(target).fetchSemanticsNode().boundsInRoot.center
        val origin = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag(tag).performTouchInput { moveTo(destination - origin, delayMillis = 240); up() }
        compose.waitForIdle()
    }
    @Test fun `hold exposes compact category prompt and delete but release alone keeps selection`() {
        renderHistory()
        hold("history-card-a")
        compose.onNodeWithTag("drop-delete").assertExists()
        compose.onNodeWithTag("drop-category-prompt").assertIsDisplayed()
        compose.onNodeWithTag("drop-category-custom").assertDoesNotExist()
        compose.onNodeWithTag("drop-category-grid").assertDoesNotExist()
        assertEquals(setOf("a"), history.value.selectedIds)
        compose.onNodeWithTag("history-card-a").performTouchInput { up() }
        compose.waitForIdle()
        compose.onNodeWithTag("drop-delete").assertDoesNotExist()
        assertEquals(setOf("a"), history.value.selectedIds)
        assertEquals(0, opened)
        assertFalse(deleted)
        assertTrue(droppedIds.isEmpty())
    }
    @Test fun `history drag into custom group uses selected ids without opening conversation`() {
        renderHistory()
        hold("history-card-a")
        dragTo("history-card-a", "drop-category-custom")
        assertEquals("custom", movedTo)
        assertEquals(setOf("a"), droppedIds)
        assertEquals(0, opened)
        assertFalse(deleted)
    }
    @Test fun `downward history drag deletes explicit selection on release`() {
        renderHistory()
        hold("history-card-a")
        dragTo("history-card-a", "drop-delete")
        assertTrue(deleted)
        assertEquals(setOf("a"), droppedIds)
    }
    @Test fun `notebook uses same long press move gesture`() {
        renderNotebook()
        hold("notebook-card-a")
        dragTo("notebook-card-a", "drop-category-custom")
        assertEquals("custom", movedTo)
        assertEquals(setOf("a"), droppedIds)
        assertEquals(0, opened)
    }
    @Test fun `dragging a selected history card moves full selection without deselecting it`() {
        history.value = history.value.copy(selectedIds = setOf("a", "b"))
        renderHistory()
        hold("history-card-a")
        assertEquals(setOf("a", "b"), history.value.selectedIds)
        dragTo("history-card-a", "drop-category-custom")
        assertEquals(setOf("a", "b"), droppedIds)
    }

    @Test fun `ordinary quick scroll does not select or trigger drop targets`() {
        history.value = history.value.copy(entries = (0..20).map { index ->
            HistoryEntry(ConversationEntity(id = "entry$index", title = "会话 $index"), "题目")
        })
        renderHistory()
        compose.onNodeWithTag("history-card-entry0").performTouchInput { swipeUp(durationMillis = 180) }
        assertTrue(history.value.selectedIds.isEmpty())
        assertEquals(0, opened)
        assertFalse(deleted)
        compose.onNodeWithTag("drop-delete").assertDoesNotExist()
    }
    @Test fun `drag leaving the delete range cancels deletion on release`() {
        renderHistory()
        hold("history-card-a")
        val destination = compose.onNodeWithTag("drop-delete").fetchSemanticsNode().boundsInRoot.center
        val origin = compose.onNodeWithTag("history-card-a").fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag("history-card-a").performTouchInput {
            moveTo(destination - origin, delayMillis = 240)
            moveTo(Offset(40f, 40f), delayMillis = 240)
            up()
        }
        assertFalse(deleted)
        assertTrue(droppedIds.isEmpty())
        assertEquals(setOf("a"), history.value.selectedIds)
    }

    @Test fun `upward entry reveals wrapping user groups and vertical scrolling reaches the last`() {
        history.value = history.value.copy(categories = (0..9).map { index ->
            NotebookCategoryEntity(id = "group$index", name = "自建分组 $index")
        })
        renderHistory()
        hold("history-card-a")
        unfold("history-card-a")
        compose.onNodeWithTag("drop-category-none").assertIsDisplayed()
        (0..9).forEach { compose.onNodeWithTag("drop-category-group$it").assertExists() }
        val first = compose.onNodeWithTag("drop-category-none").fetchSemanticsNode().boundsInRoot
        val nextRow = compose.onNodeWithTag("drop-category-group2").fetchSemanticsNode().boundsInRoot
        assertTrue(nextRow.top >= first.bottom)
        val grid = compose.onNodeWithTag("drop-category-grid").fetchSemanticsNode()
        assertTrue(grid.config.contains(SemanticsProperties.VerticalScrollAxisRange))
        assertFalse(grid.config.contains(SemanticsProperties.HorizontalScrollAxisRange))
        compose.onNodeWithTag("drop-category-group9").performScrollTo().assertIsDisplayed()
        // Moving back to the prompt does not collapse the grid or reset its vertical scroll.
        dragTo("history-card-a", "drop-category-group9")
        assertEquals("group9", movedTo)
        assertEquals(setOf("a"), droppedIds)
    }

    @Test fun `category grid stays expanded when the held card leaves the upper region`() {
        val drag = renderCard()
        hold("component-card")
        unfold("component-card")
        moveTo("component-card", "drop-delete")
        compose.runOnIdle {
            assertTrue(drag.categoriesExpanded)
            assertEquals(PaperDropTarget.Delete, drag.hovered)
        }
        compose.onNodeWithTag("drop-category-custom").assertIsDisplayed()
        compose.onNodeWithTag("component-card").performTouchInput { cancel() }
        compose.waitForIdle()
        compose.onNodeWithTag("drop-category-grid").assertDoesNotExist()
        assertTrue(droppedIds.isEmpty())
    }

    @Test fun `uncategorized remains available as an explicit move destination`() {
        renderHistory()
        hold("history-card-a")
        dragTo("history-card-a", "drop-category-none")
        assertEquals(setOf("a"), droppedIds)
        assertNull(movedTo)
        assertFalse(deleted)
    }

    @Test fun `category names wrap fully instead of truncating in a carousel`() {
        val fullName = "这个用户自建分组名称需要完整显示且超过两行文字长度"
        history.value = history.value.copy(categories = listOf(category.copy(name = fullName)))
        renderCard()
        hold("component-card")
        unfold("component-card")
        compose.onNodeWithText(fullName).assertIsDisplayed()
        val text = compose.onNodeWithText(fullName).fetchSemanticsNode().boundsInRoot
        val categoryBounds = compose.onNodeWithTag("drop-category-custom").fetchSemanticsNode().boundsInRoot
        assertTrue(text.bottom <= categoryBounds.bottom)
    }

    @Test fun `small floating preview retains exact source width height and all card content`() {
        val drag = renderCard(width = 120.dp, height = 250.dp)
        val original = compose.onNodeWithTag("component-card").fetchSemanticsNode().boundsInRoot
        hold("component-card")
        val floating = compose.onNodeWithTag("drag-paper").fetchSemanticsNode().boundsInRoot
        assertEquals(original.width, floating.width, 1f)
        assertEquals(original.height, floating.height, 1f)
        assertEquals(original.left, floating.left, 1f)
        assertEquals(original.top, floating.top, 1f)
        compose.onNode(hasText("原始标题") and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText(previewText.value) and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasContentDescription("原有缩略图") and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasTestTag("card-metadata") and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("已选 1 项 · 拖离目标可取消").assertDoesNotExist()
        compose.runOnIdle { assertNotNull(drag.visual!!.preview) }
        compose.onNodeWithTag("component-card").performTouchInput { up() }
    }

    @Test fun `wide floating preview moves with no clamp scale or rotation`() {
        renderCard(width = 340.dp, height = 250.dp)
        val original = compose.onNodeWithTag("component-card").fetchSemanticsNode().boundsInRoot
        hold("component-card")
        compose.onNodeWithTag("component-card").performTouchInput { moveBy(Offset(0f, -20f), delayMillis = 240) }
        compose.waitForIdle()
        val floating = compose.onNodeWithTag("drag-paper").fetchSemanticsNode().boundsInRoot
        assertEquals(original.width, floating.width, 1f)
        assertEquals(original.height, floating.height, 1f)
        assertEquals(original.left, floating.left, 1f)
        assertEquals(original.top - 20f, floating.top, 1f)
        compose.onNodeWithTag("component-card").performTouchInput { cancel() }
    }

    @Test fun `partially clipped source lifts at full measured card size`() {
        val drag = renderCard(width = 120.dp, height = 250.dp, sourceViewportHeight = 100.dp)
        val source = compose.onNodeWithTag("component-card").fetchSemanticsNode().boundsInRoot
        hold("component-card")
        val floating = compose.onNodeWithTag("drag-paper").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle {
            assertTrue(drag.visual!!.origin.height > source.height)
            assertEquals(drag.visual!!.origin.width, floating.width, 1f)
            assertEquals(drag.visual!!.origin.height, floating.height, 1f)
        }
        compose.onNodeWithTag("component-card").performTouchInput { cancel() }
    }

    @Test fun `preview captured at drag start survives later source recomposition`() {
        val drag = renderCard()
        hold("component-card")
        val originalText = previewText.value
        var capturedPreview: (@Composable () -> Unit)? = null
        compose.runOnIdle { capturedPreview = drag.visual!!.preview }
        compose.runOnIdle { previewText.value = "修改后的内容" }
        compose.onNode(hasText(originalText) and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("修改后的内容") and hasAnyAncestor(hasTestTag("component-card")), useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertSame(capturedPreview, drag.visual!!.preview) }
        compose.onNodeWithTag("component-card").performTouchInput { cancel() }
    }

    @Test fun `removing a highlighted category unregisters it before release`() {
        val drag = renderCard()
        hold("component-card")
        unfold("component-card")
        moveTo("component-card", "drop-category-custom")
        compose.runOnIdle { assertEquals(PaperDropTarget.Category(category.id, category.name), drag.hovered) }
        compose.runOnIdle { history.value = history.value.copy(categories = emptyList()) }
        compose.onNodeWithTag("drop-category-custom").assertDoesNotExist()
        compose.runOnIdle { assertNull(drag.hovered) }
        compose.onNodeWithTag("component-card").performTouchInput { up() }
        assertTrue(droppedIds.isEmpty())
    }

    @Test fun `offscreen category cannot receive drop while grid is vertically scrolled`() {
        history.value = history.value.copy(categories = (0..29).map { index ->
            NotebookCategoryEntity(id = "group$index", name = "自建分组 $index")
        })
        val drag = renderCard()
        hold("component-card")
        unfold("component-card")
        val first = compose.onNodeWithTag("drop-category-none").fetchSemanticsNode().boundsInRoot.center
        compose.onNodeWithTag("drop-category-group29").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("drop-category-none").assertIsNotDisplayed()
        compose.runOnIdle {
            drag.move(first - drag.visual!!.pointer)
            assertNotEquals(PaperDropTarget.Category(null, "未分类"), drag.hovered)
        }
        compose.onNodeWithTag("component-card").performTouchInput { cancel() }
        assertTrue(droppedIds.isEmpty())
    }
    @Test fun `category grid unfolds downward through intermediate heights`() {
        history.value = history.value.copy(categories = (0..5).map { index ->
            NotebookCategoryEntity(id = "group$index", name = "自建分组 $index")
        })
        val drag = renderCard()
        hold("component-card")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { drag.move(drag.categoryPromptBounds.center - drag.visual!!.pointer) }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(80)
        compose.waitForIdle()
        var openingHeight = 0f
        compose.runOnIdle { openingHeight = drag.categoryBounds.height }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("The grid must reveal an intermediate height", openingHeight > 0f)
            assertTrue("The grid must keep expanding downward", openingHeight < drag.categoryBounds.height)
            assertTrue(drag.categoriesExpanded)
        }
        compose.onNodeWithTag("component-card").performTouchInput { cancel() }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    @Test fun `cancelled preview keeps its size and content during return animation then clears`() {
        val drag = renderCard()
        val original = compose.onNodeWithTag("component-card").fetchSemanticsNode().boundsInRoot
        hold("component-card")
        compose.onNodeWithTag("component-card").performTouchInput { moveBy(Offset(0f, -20f), delayMillis = 240) }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("component-card").performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(96)
        compose.waitForIdle()
        val returning = compose.onNodeWithTag("drag-paper").fetchSemanticsNode().boundsInRoot
        assertEquals(original.width, returning.width, 1f)
        assertEquals(original.height, returning.height, 1f)
        compose.onNode(hasText("原始标题") and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertExists()
        compose.runOnIdle {
            assertTrue(drag.settling)
            assertEquals(drag.visual!!.start, drag.visual!!.pointer)
            assertNotNull(drag.visual!!.preview)
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("drag-paper").assertDoesNotExist()
        compose.runOnIdle { assertFalse(drag.settling) }
    }

}
