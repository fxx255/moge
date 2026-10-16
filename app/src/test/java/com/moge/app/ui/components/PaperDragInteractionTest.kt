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
import androidx.compose.ui.platform.LocalView
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
    private lateinit var sampleView: android.view.View

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
            sampleView = LocalView.current
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
                Column(Modifier.align(Alignment.TopCenter)) {
                    PaperSearchPanel("", {}, "搜索", enabled = false,
                        filtersModifier = Modifier.paperCategoryActivation(drag),
                        expandedContent = { PaperCategoryDropPanel(drag, history.value.categories) }) {
                        PaperCategoryFilter(true, {}, "全部", enabled = false)
                    }
                }
                PaperDragOverlay(drag, Modifier.matchParentSize())
            }
        } } }
        return drag
    }
    private fun renderHistory() {
        compose.setContent { MogeTheme {
            sampleView = LocalView.current
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
    private fun assertLiftedCardHasNoTape(sourceTag: String) {
        compose.onNode(hasTestTag("paper-card-tape") and hasAnyAncestor(hasTestTag("drag-paper")),
            useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasTestTag("paper-card-tape") and hasAnyAncestor(hasTestTag(sourceTag)),
            useUnmergedTree = true).assertExists()
    }
    @Test fun `hold exposes compact category prompt and delete but release alone keeps selection`() {
        renderHistory()
        hold("history-card-a")
        assertLiftedCardHasNoTape("history-card-a")
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
    @Test @Config(qualifiers = "+night")
    fun `existing filter row unfolds categories with a page sized curved delete area`() {
        renderHistory()
        hold("history-card-a")
        moveTo("history-card-a", "history-category-filters")
        compose.onNodeWithTag("drop-category-grid").assertIsDisplayed()
        compose.onNodeWithContentDescription("搜索标题、消息或识别文本").assertIsDisplayed()
        compose.onNodeWithTag("history-category-filters").assertIsDisplayed()
        val panel = compose.onNodeWithTag("paper-search-panel").fetchSemanticsNode().boundsInRoot
        val filters = compose.onNodeWithTag("history-category-filters").fetchSemanticsNode().boundsInRoot
        val prompt = compose.onNodeWithTag("drop-category-prompt").fetchSemanticsNode().boundsInRoot
        val grid = compose.onNodeWithTag("drop-category-grid").fetchSemanticsNode().boundsInRoot
        val firstLabel = compose.onNode(hasText("未分类") and hasAnyAncestor(hasTestTag("drop-category-none")))
            .getUnclippedBoundsInRoot()
        val gridBounds = compose.onNodeWithTag("drop-category-grid").getUnclippedBoundsInRoot()
        exportPocketSample("chalk")
        assertTrue("A small screen must show the entire category label: $firstLabel in $gridBounds",
            firstLabel.top >= gridBounds.top && firstLabel.bottom <= gridBounds.bottom)
        assertTrue("The original filters remain above the prompt", filters.bottom <= prompt.top)
        assertTrue("The expanded targets belong to the original search sheet", panel.contains(grid.topLeft) && grid.bottom <= panel.bottom)
        compose.onNode(hasTestTag("drop-category-grid") and hasAnyAncestor(hasTestTag("paper-search-panel"))).assertExists()
        val card = compose.onNodeWithTag("history-card-a").fetchSemanticsNode().boundsInRoot
        assertTrue("The expanded search sheet reserves space above the cards", card.top >= panel.bottom)
        val zone = compose.onNodeWithTag("drop-delete").fetchSemanticsNode().boundsInRoot
        val root = compose.onNodeWithTag("paper-drag-overlay").fetchSemanticsNode().boundsInRoot
        assertEquals("The visible and active delete region shrink together by 20 percent", root.height * .24f, zone.height, 1f)
        compose.onNodeWithTag("history-card-a").performTouchInput { cancel() }
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
        assertLiftedCardHasNoTape("notebook-card-a")
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
        val deleteBounds = compose.onNodeWithTag("drop-delete").fetchSemanticsNode().boundsInRoot
        val destination = deleteBounds.center
        // Leave the actual delete target, independently of the toolbar height
        // and where the first card happens to sit on a small screen.
        val safePoint = Offset(deleteBounds.center.x, deleteBounds.top - 24f)
        val origin = compose.onNodeWithTag("history-card-a").fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag("history-card-a").performTouchInput {
            moveTo(destination - origin, delayMillis = 240)
            moveTo(safePoint - origin, delayMillis = 240)
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
        // Lower rows may be fully clipped on a small viewport. Compare their
        // layout positions before scrolling them into the visible drop region.
        val first = compose.onNodeWithTag("drop-category-none").getUnclippedBoundsInRoot()
        val nextRow = compose.onNodeWithTag("drop-category-group2").getUnclippedBoundsInRoot()
        assertTrue("Groups must wrap into rows: $first then $nextRow", nextRow.top >= first.bottom)
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
        exportPocketSample("paper-long-label")
    }

    private fun exportPocketSample(name: String) {
        if (System.getenv("PAPER_CATEGORY_EXPORT_SAMPLES") != "1") return
        compose.runOnIdle {
            val root = sampleView.rootView
            val image = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                root.draw(android.graphics.Canvas(image))
                val file = java.io.File("build/category-pocket-samples/$name.png").apply { parentFile?.mkdirs() }
                file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            } finally { image.recycle() }
        }
    }

    @Test fun `small floating preview preserves content and anchors its grab to the actual pointer`() {
        val drag = renderCard(width = 120.dp, height = 250.dp)
        val original = compose.onNodeWithTag("component-card").fetchSemanticsNode().boundsInRoot
        hold("component-card")
        val floating = compose.onNodeWithTag("drag-paper").fetchSemanticsNode().boundsInRoot
        assertEquals(original.width, floating.width, 1f)
        assertEquals(original.height, floating.height, 1f)
        compose.runOnIdle {
            val visual = drag.visual!!
            val scale = floating.width / original.width
            assertEquals(visual.pointer.x, floating.left + visual.grab.x * scale, 1f)
            assertEquals(visual.pointer.y, floating.top + visual.grab.y * scale, 1f)
        }
        compose.onNode(hasText("原始标题") and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText(previewText.value) and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasContentDescription("原有缩略图") and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasTestTag("card-metadata") and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("已选 1 项 · 拖离目标可取消").assertDoesNotExist()
        compose.runOnIdle { assertNotNull(drag.visual!!.preview) }
        compose.onNodeWithTag("component-card").performTouchInput { up() }
    }

    @Test fun `wide floating preview shrinks uniformly while the grab stays under the finger`() {
        renderCard(width = 340.dp, height = 250.dp)
        val original = compose.onNodeWithTag("component-card").fetchSemanticsNode().boundsInRoot
        hold("component-card")
        compose.onNodeWithTag("component-card").performTouchInput { moveBy(Offset(0f, -20f), delayMillis = 240) }
        compose.waitForIdle()
        val floating = compose.onNodeWithTag("drag-paper").fetchSemanticsNode().boundsInRoot
        assertTrue(floating.width < original.width)
        assertTrue(floating.height < original.height)
        assertEquals(original.width / original.height, floating.width / floating.height, 0.001f)
        assertEquals(original.center.x, floating.center.x, 1f)
        assertEquals(original.center.y - 20f, floating.center.y, 1f)
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

    @Test fun `expanded groups scroll with a stationary held card at either edge and stop outside the grid`() {
        history.value = history.value.copy(categories = (0..29).map { index ->
            NotebookCategoryEntity(id = "group$index", name = "自建分组 $index")
        })
        val drag = renderCard()
        hold("component-card")
        unfold("component-card")
        val grid = compose.onNodeWithTag("drop-category-grid").fetchSemanticsNode().boundsInRoot
        val firstTarget = PaperDropTarget.Category(null, "未分类")
        val firstTop = compose.onNodeWithTag("drop-category-none").fetchSemanticsNode().positionInRoot.y
        compose.mainClock.autoAdvance = false
        fun point(point: Offset) = compose.runOnIdle { drag.move(point - drag.visual!!.pointer) }
        fun advance() {
            // Let layout consume each scroll frame, just as the display does while a finger is held.
            repeat(24) { compose.mainClock.advanceTimeBy(32); compose.waitForIdle() }
        }
        try {
            point(Offset(grid.center.x, grid.bottom - 3f))
            advance()
            val downTop = compose.onNodeWithTag("drop-category-none").fetchSemanticsNode().positionInRoot.y
            assertTrue("Holding at the lower edge must reveal later groups: first=$firstTop down=$downTop grid=$grid category=${drag.categoryBounds} delete=${drag.deleteBounds} pointer=${drag.visual?.pointer}", downTop < firstTop - 40f)
            compose.runOnIdle { assertNotEquals(firstTarget, drag.hovered) }
            point(grid.center)
            advance()
            val neutralTop = compose.onNodeWithTag("drop-category-none").fetchSemanticsNode().positionInRoot.y
            advance()
            assertEquals(neutralTop, compose.onNodeWithTag("drop-category-none").fetchSemanticsNode().positionInRoot.y, 1f)
            point(Offset(grid.center.x, grid.top + 3f))
            advance()
            val upTop = compose.onNodeWithTag("drop-category-none").fetchSemanticsNode().positionInRoot.y
            assertTrue("Holding at the upper edge must return towards earlier groups", upTop > neutralTop + 40f)
            point(Offset(grid.right + 8f, grid.bottom - 3f))
            advance()
            val outsideTop = compose.onNodeWithTag("drop-category-none").fetchSemanticsNode().positionInRoot.y
            advance()
            assertEquals(outsideTop, compose.onNodeWithTag("drop-category-none").fetchSemanticsNode().positionInRoot.y, 1f)
        } finally {
            compose.onNodeWithTag("component-card").performTouchInput { cancel() }
            compose.mainClock.autoAdvance = true
        }
        assertTrue(droppedIds.isEmpty())
    }

    @Test fun `cancelled preview retains its size and content until the quick return finishes`() {
        val drag = renderCard()
        val original = compose.onNodeWithTag("component-card").fetchSemanticsNode().boundsInRoot
        hold("component-card")
        compose.onNodeWithTag("component-card").performTouchInput { moveBy(Offset(0f, -20f), delayMillis = 240) }
        compose.waitForIdle()
        val held = compose.onNodeWithTag("drag-paper").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("component-card").performTouchInput { cancel() }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            // Prime recomposition and the first animation frame, then sample playback.
            compose.mainClock.advanceTimeBy(48)
            compose.waitForIdle()
            val returning = compose.onNodeWithTag("drag-paper").fetchSemanticsNode().boundsInRoot
            assertTrue("The return must include intermediate sizes: $held -> $returning -> $original",
                returning.width > held.width && returning.width < original.width)
            assertTrue(returning.height > held.height && returning.height < original.height)
            compose.onNode(hasText("原始标题") and hasAnyAncestor(hasTestTag("drag-paper")), useUnmergedTree = true).assertExists()
            compose.runOnIdle {
                assertTrue(drag.settling)
                assertEquals(drag.visual!!.start, drag.visual!!.pointer)
                assertNotNull(drag.visual!!.preview)
            }
            compose.mainClock.advanceTimeBy(112)
            compose.waitForIdle()
            compose.onNodeWithTag("drag-paper").assertDoesNotExist()
            compose.runOnIdle { assertFalse(drag.settling) }
        } finally { compose.mainClock.autoAdvance = true }
    }

}
