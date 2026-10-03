package com.moge.app.ui.components

import android.app.Application
import org.robolectric.annotation.GraphicsMode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.data.db.*
import com.moge.app.ui.history.HistoryContent
import com.moge.app.ui.history.HistoryUiState
import com.moge.app.ui.notebook.NotebookContent
import com.moge.app.ui.notebook.NotebookUiState
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
    private fun dragTo(tag: String, target: String) {
        val destination = compose.onNodeWithTag(target).fetchSemanticsNode().boundsInRoot.center
        val origin = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag(tag).performTouchInput { moveTo(destination - origin, delayMillis = 240); up() }
        compose.waitForIdle()
    }
    @Test fun `hold exposes delete and categories but release alone keeps selection`() {
        renderHistory()
        hold("history-card-a")
        compose.onNodeWithTag("drop-delete").assertExists()
        compose.onNodeWithTag("drop-category-custom").assertExists()
        assertEquals(setOf("a"), history.value.selectedIds)
        compose.onNodeWithTag("history-card-a").performTouchInput { up() }
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

    @Test fun `edge hover reveals many user groups and allows dropping in the last group`() {
        history.value = history.value.copy(categories = (0..9).map { index ->
            NotebookCategoryEntity(id = "group$index", name = "自建分组 $index")
        })
        renderHistory()
        hold("history-card-a")
        val first = compose.onNodeWithTag("drop-category-none").fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val origin = compose.onNodeWithTag("history-card-a").fetchSemanticsNode().boundsInRoot.topLeft
        compose.onNodeWithTag("history-card-a").performTouchInput {
            moveTo(Offset(root.right - 14f, first.center.y) - origin, delayMillis = 240)
        }
        compose.waitForIdle()
        compose.onNodeWithTag("drop-category-group9").assertIsDisplayed()
        dragTo("history-card-a", "drop-category-group9")
        assertEquals("group9", movedTo)
        assertEquals(setOf("a"), droppedIds)
    }
}
