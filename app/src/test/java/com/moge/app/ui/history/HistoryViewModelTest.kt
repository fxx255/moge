package com.moge.app.ui.history

import androidx.lifecycle.SavedStateHandle
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.HistoryEntry
import com.moge.app.runtime.DraftStore
import com.moge.app.runtime.GenerationManager
import com.moge.app.runtime.GenerationManager.ActiveState
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = mockk<ConversationRepository>()
    private val drafts = mockk<DraftStore>(relaxed = true)
    private val active = MutableStateFlow(ActiveState())
    private val manager = mockk<GenerationManager> { every { this@mockk.active } returns this@HistoryViewModelTest.active }
    private fun entry(id: String) = HistoryEntry(ConversationEntity(id = id, title = id), "题目$id")
    private val entries = MutableStateFlow(listOf(entry("a"), entry("b")))

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeHistory(any()) } returns entries
    }
    @After fun teardown() { Dispatchers.resetMain() }
    private fun vm(handle: SavedStateHandle = SavedStateHandle()) = HistoryViewModel(handle, repository, drafts, manager)

    @Test fun `search restores and clears hidden selections`() {
        val handle = SavedStateHandle(mapOf("historyQuery" to "函数"))
        val model = vm(handle)
        verify { repository.observeHistory("函数") }
        model.toggleSelection("a")
        model.setQuery("速度")
        assertTrue(model.state.value.selectedIds.isEmpty())
        assertEquals("速度", handle.get<String>("historyQuery"))
        dispatcher.scheduler.advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        verify { repository.observeHistory("速度") }
    }

    @Test fun `typing is debounced and keeps showing previous results`() {
        val model = vm()
        model.setQuery("函")
        model.setQuery("函数")
        assertEquals(2, model.state.value.entries.size)
        verify(exactly = 0) { repository.observeHistory("函") }
        verify(exactly = 0) { repository.observeHistory("函数") }
        dispatcher.scheduler.advanceTimeBy(SEARCH_DEBOUNCE_MS + 1)
        verify(exactly = 1) { repository.observeHistory("函数") }
        verify(exactly = 0) { repository.observeHistory("函") }
        model.setQuery("")
        verify(exactly = 2) { repository.observeHistory("") }
    }

    @Test fun `selection restores prunes removed rows and select all covers current results`() {
        val model = vm(SavedStateHandle(mapOf("historySelected" to arrayListOf("a", "gone"))))
        assertEquals(setOf("a"), model.state.value.selectedIds)
        model.selectAll()
        assertEquals(setOf("a", "b"), model.state.value.selectedIds)
        entries.value = listOf(entry("b"))
        assertEquals(setOf("b"), model.state.value.selectedIds)
        model.clearSelection()
        assertFalse(model.state.value.selecting)
    }

    @Test fun `rename requires single selection and preserves selection after validation error`() {
        val model = vm()
        model.selectAll()
        model.renameSelected("新标题")
        coVerify(exactly = 0) { repository.rename(any(), any()) }
        model.toggleSelection("b")
        coEvery { repository.rename("a", " ") } throws IllegalArgumentException("标题不能为空")
        model.renameSelected(" ")
        assertEquals(setOf("a"), model.state.value.selectedIds)
        assertEquals("标题不能为空", model.state.value.message)
        coEvery { repository.rename("a", "新标题") } returns true
        model.renameSelected("新标题")
        assertEquals("标题已修改", model.state.value.message)
        assertFalse(model.state.value.selecting)
    }

    @Test fun `delete excludes active owner and clears drafts only for actually deleted rows`() {
        active.value = ActiveState(requestId = "r", conversationId = "b")
        val model = vm()
        model.selectAll()
        coEvery { repository.deleteIdle(setOf("a")) } returns setOf("a")
        model.deleteSelected()
        coVerify(exactly = 1) { drafts.clear("a") }
        coVerify(exactly = 0) { drafts.clear("b") }
        assertEquals(setOf("b"), model.state.value.selectedIds)
        assertTrue(model.state.value.message!!.contains("生成中的对话已保留"))
        assertFalse(model.state.value.busy)
    }

    @Test fun `database protection still applies when active flow has not updated`() {
        val model = vm()
        model.selectAll()
        coEvery { repository.deleteIdle(setOf("a", "b")) } returns setOf("a")
        model.deleteSelected()
        assertEquals(setOf("b"), model.state.value.selectedIds)
        coVerify(exactly = 0) { drafts.clear("b") }
    }

    @Test fun `pin uses repository and clears selection`() {
        val model = vm()
        model.toggleSelection("a")
        coEvery { repository.setPinned("a", true) } just Runs
        model.pinSelected()
        coVerify { repository.setPinned("a", true) }
        assertFalse(model.state.value.selecting)
    }

    @Test fun `query failure exposes retry and retry subscribes again`() {
        every { repository.observeHistory(any()) } returns flow { throw IllegalStateException("disk") } andThen entries
        val model = vm()
        assertNotNull(model.state.value.error)
        model.retry()
        assertNull(model.state.value.error)
        assertEquals(2, model.state.value.entries.size)
    }
}
