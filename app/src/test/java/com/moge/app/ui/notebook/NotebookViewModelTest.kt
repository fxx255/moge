package com.moge.app.ui.notebook

import androidx.lifecycle.SavedStateHandle
import com.moge.app.data.db.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotebookViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<NotebookRepository>()
    private val history = mockk<ConversationRepository>()
    private val categories = MutableStateFlow(listOf(NotebookCategoryEntity(id = "category", name = "自定义")))
    private fun entry(id: String) = NotebookEntryEntity(id = id, sourceConversationId = "source",
        sourceQuestionId = "q$id", sourceAnswerId = "a$id", title = id, questionText = "问题", answerText = "解答")
    private val entries = MutableStateFlow(listOf(entry("a"), entry("b")))
    private val detail = MutableStateFlow<NotebookEntryEntity?>(entry("a"))
    private val source = MutableStateFlow<ConversationEntity?>(ConversationEntity(id = "source", title = "源会话"))
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeCategories() } returns categories
        every { repository.observeEntries(any(), any(), any()) } returns entries
        every { repository.observeEntry(any()) } returns detail
        every { history.observeConversation(any()) } returns source
    }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = NotebookViewModel(handle, repository, history)

    @Test fun `category and query restore and changing filters clears selection`() {
        val handle = SavedStateHandle(mapOf("notebookQuery" to "函数", "notebookCategory" to "category"))
        val model = vm(handle)
        verify { repository.observeEntries("函数", "category", false) }
        model.toggleSelection("a")
        model.setCategory(null, true)
        assertTrue(model.state.value.selectedIds.isEmpty())
        verify { repository.observeEntries("函数", null, true) }
        model.setQuery("速度")
        dispatcher.scheduler.advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        verify { repository.observeEntries("速度", null, true) }
    }
    @Test fun `typing is debounced and clear immediately reloads`() {
        val model = vm()
        model.setQuery("函")
        model.setQuery("函数")
        verify(exactly = 0) { repository.observeEntries("函", any(), any()) }
        verify(exactly = 0) { repository.observeEntries("函数", any(), any()) }
        assertEquals(2, model.state.value.entries.size)
        dispatcher.scheduler.advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        verify { repository.observeEntries("函数", null, false) }
        model.setQuery("")
        verify(exactly = 2) { repository.observeEntries("", null, false) }
    }
    @Test fun `snapshot remains open after source disappears and detail restores`() {
        val handle = SavedStateHandle(mapOf("notebookOpenEntry" to "a"))
        val model = vm(handle)
        assertEquals("a", model.state.value.detailEntry!!.id)
        assertTrue(model.state.value.sourceExists)
        source.value = null
        assertFalse(model.state.value.sourceExists)
        assertEquals("解答", model.state.value.detailEntry!!.answerText)
        model.closeEntry()
        assertNull(model.state.value.openEntryId)
        assertEquals("", handle.get<String>("notebookOpenEntry"))
    }
    @Test fun `bulk move and favorite deletion never delete source conversation`() {
        val model = vm()
        model.selectAll()
        coEvery { repository.moveEntries(setOf("a", "b"), "category") } returns 2
        model.moveSelected("category")
        coVerify { repository.moveEntries(setOf("a", "b"), "category") }
        assertFalse(model.state.value.selecting)
        model.openEntry("a")
        coEvery { repository.deleteFavorites(setOf("a")) } returns 1
        model.deleteSelected()
        assertNull(model.state.value.openEntryId)
        coVerify(exactly = 0) { history.deleteIdle(any()) }
        coVerify(exactly = 0) { history.deleteAllIdle() }
    }
    @Test fun `selection prunes disappeared favorites and deleting active category selects uncategorized`() {
        val model = vm()
        model.selectAll()
        entries.value = listOf(entry("b"))
        assertEquals(setOf("b"), model.state.value.selectedIds)
        model.setCategory("category")
        coEvery { repository.deleteCategory("category") } returns true
        model.deleteCategory("category")
        assertTrue(model.state.value.uncategorizedOnly)
        assertNull(model.state.value.categoryId)
    }
    @Test fun `entry read errors retry without losing list or source data`() {
        every { repository.observeEntries(any(), any(), any()) } returns flow { error("disk") } andThen entries
        val model = vm()
        assertNotNull(model.state.value.error)
        model.retry()
        assertNull(model.state.value.error)
        assertEquals(2, model.state.value.entries.size)
    }
    @Test fun `failed category operation exposes validation and releases busy state`() {
        val model = vm()
        coEvery { repository.createCategory(" ") } throws IllegalArgumentException("名称不能为空")
        model.createCategory(" ")
        assertEquals("名称不能为空", model.state.value.message)
        assertFalse(model.state.value.busy)
    }

    @Test fun `drag moves explicit snapshot ids without depending on current selection`() {
        coEvery { repository.moveEntries(setOf("a"), "category") } returns 1
        val model = vm()
        model.moveItems(setOf("a"), "category")
        coVerify { repository.moveEntries(setOf("a"), "category") }
        assertTrue(model.state.value.message!!.contains("1"))
    }
    @Test fun `drag removal only deletes favorites and preserves history`() {
        coEvery { repository.deleteFavorites(setOf("a")) } returns 1
        val model = vm()
        model.deleteItems(setOf("a"))
        coVerify { repository.deleteFavorites(setOf("a")) }
        coVerify(exactly = 0) { history.deleteIdle(any()) }
        assertTrue(model.state.value.message!!.contains("历史对话仍保留"))
    }

    @Test fun `route favorite loads independently of stale query and category and closing survives recreation`() {
        every { repository.observeEntries("old search", "category", false) } returns MutableStateFlow<List<NotebookEntryEntity>>(emptyList())
        val handle = SavedStateHandle(mapOf("notebookEntryId" to "a", "notebookQuery" to "old search",
            "notebookCategory" to "category"))
        val model = vm(handle)
        assertEquals("a", model.state.value.openEntryId)
        assertEquals("a", model.state.value.detailEntry!!.id)
        assertTrue(model.state.value.entries.isEmpty())
        assertEquals("old search", model.state.value.query)
        assertEquals("category", model.state.value.categoryId)
        verify { repository.observeEntry("a") }
        model.closeEntry()
        val restored = vm(handle)
        assertNull(restored.state.value.openEntryId)
        assertNull(restored.state.value.detailEntry)
        assertEquals("", handle.get<String>("notebookOpenEntry"))
    }

    @Test fun `explicit restored open key overrides navigation argument including empty value`() {
        val opened = vm(SavedStateHandle(mapOf("notebookEntryId" to "b", "notebookOpenEntry" to "a")))
        assertEquals("a", opened.state.value.openEntryId)
        val closed = vm(SavedStateHandle(mapOf("notebookEntryId" to "b", "notebookOpenEntry" to "")))
        assertNull(closed.state.value.openEntryId)
        verify(exactly = 0) { repository.observeEntry("b") }
    }

    @Test fun `changing detail cancels old source immediately even when new favorite has not loaded`() {
        var oldSourceCancelled = false
        var pendingEntryCancelled = false
        every { history.observeConversation("source") } returns flow {
            try { emit(source.value); awaitCancellation() }
            finally { oldSourceCancelled = true }
        }
        every { repository.observeEntry("b") } returns flow {
            try { awaitCancellation() }
            finally { pendingEntryCancelled = true }
        }
        val model = vm(SavedStateHandle(mapOf("notebookOpenEntry" to "a")))
        assertTrue(model.state.value.sourceExists)
        model.openEntry("b")
        assertTrue(oldSourceCancelled)
        assertEquals("b", model.state.value.openEntryId)
        assertNull(model.state.value.detailEntry)
        assertFalse(model.state.value.sourceExists)
        model.closeEntry()
        assertTrue(pendingEntryCancelled)
        assertNull(model.state.value.openEntryId)
    }

    @Test fun `changed favorite loads its own detail and old favorite updates cannot replace it`() {
        val second = MutableStateFlow<NotebookEntryEntity?>(entry("b").copy(sourceConversationId = "second"))
        every { repository.observeEntry("b") } returns second
        every { history.observeConversation("second") } returns MutableStateFlow(null)
        val model = vm(SavedStateHandle(mapOf("notebookEntryId" to "a")))
        model.openEntry("b")
        assertEquals("b", model.state.value.detailEntry!!.id)
        assertFalse(model.state.value.sourceExists)
        detail.value = entry("obsolete")
        assertEquals("b", model.state.value.detailEntry!!.id)
        second.value = second.value!!.copy(answerText = "updated answer")
        assertEquals("updated answer", model.state.value.detailEntry!!.answerText)
    }
}
