package com.moge.app.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.HistoryEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationNavigationViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val repository = mockk<ConversationRepository>()
    private val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val models = mutableListOf<ConversationNavigationViewModel>()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { repository.observeHistory(any(), any(), any()) } returns history
    }

    @After fun tearDown() {
        models.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) =
        ConversationNavigationViewModel(handle, repository).also { models += it }

    private fun entry(id: String, updatedAt: Long, pinned: Boolean = false) = HistoryEntry(
        ConversationEntity(id = id, title = id, updatedAt = Instant.ofEpochSecond(updatedAt), pinned = pinned),
        recentContent = "question $id",
    )

    private fun TestScope.subscribe(model: ConversationNavigationViewModel) =
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.previousConversationId.collect() }

    @Test fun `empty history has no previous conversation`() = runTest(dispatcher) {
        val model = vm()
        subscribe(model)
        runCurrent()
        assertNull(model.previousConversationId.value)
        assertEquals(1, history.subscriptionCount.value)
    }

    @Test fun `fallback chooses greatest update time rather than first pinned row`() = runTest(dispatcher) {
        history.value = listOf(entry("pinned", 10, pinned = true), entry("middle", 20), entry("newest", 30))
        val model = vm()
        subscribe(model)
        runCurrent()
        assertEquals("newest", model.previousConversationId.value)
        history.value = listOf(entry("pinned", 10, pinned = true), entry("middle", 40), entry("newest", 30))
        runCurrent()
        assertEquals("middle", model.previousConversationId.value)
    }

    @Test fun `saved preference is initially available and retained when it still exists`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("previousConversationId" to "preferred"))
        history.value = listOf(entry("newest", 100, pinned = true), entry("preferred", 1))
        val model = vm(handle)
        assertEquals("preferred", model.previousConversationId.value)
        assertEquals(0, history.subscriptionCount.value)
        subscribe(model)
        runCurrent()
        assertEquals("preferred", model.previousConversationId.value)
        history.value = listOf(entry("another", 200), entry("preferred", 1))
        runCurrent()
        assertEquals("preferred", model.previousConversationId.value)
    }

    @Test fun `deleted saved preference falls back to freshest existing conversation`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("previousConversationId" to "deleted"))
        history.value = listOf(entry("pinned", 10, pinned = true), entry("freshest", 100))
        val model = vm(handle)
        subscribe(model)
        runCurrent()
        assertEquals("freshest", model.previousConversationId.value)
        assertEquals("deleted", handle.get<String>("previousConversationId"))
    }

    @Test fun `deletion clearing and restoration revalidate the preferred conversation`() = runTest(dispatcher) {
        val model = vm()
        model.rememberConversation("preferred")
        history.value = listOf(entry("pinned", 1, pinned = true), entry("preferred", 10), entry("latest", 20))
        subscribe(model)
        runCurrent()
        assertEquals("preferred", model.previousConversationId.value)
        history.value = listOf(entry("pinned", 1, pinned = true), entry("latest", 20))
        runCurrent()
        assertEquals("latest", model.previousConversationId.value)
        history.value = listOf(entry("pinned", 1, pinned = true))
        runCurrent()
        assertEquals("pinned", model.previousConversationId.value)
        history.value = emptyList()
        runCurrent()
        assertNull(model.previousConversationId.value)
        history.value = listOf(entry("latest", 20), entry("preferred", 10))
        runCurrent()
        assertEquals("preferred", model.previousConversationId.value)
    }

    @Test fun `remembered conversation overrides fallback and survives saved state restoration`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        history.value = listOf(entry("latest", 100), entry("opened", 1))
        val model = vm(handle)
        subscribe(model)
        runCurrent()
        assertEquals("latest", model.previousConversationId.value)
        model.rememberConversation("opened")
        runCurrent()
        assertEquals("opened", model.previousConversationId.value)
        assertEquals("opened", handle.get<String>("previousConversationId"))
        val restored = vm(SavedStateHandle(mapOf("previousConversationId" to handle.get<String>("previousConversationId"))))
        assertEquals("opened", restored.previousConversationId.value)
        subscribe(restored)
        runCurrent()
        assertEquals("opened", restored.previousConversationId.value)
    }

    @Test fun `null and blank new page IDs preserve the last existing conversation`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        history.value = listOf(entry("latest", 100), entry("opened", 1))
        val model = vm(handle)
        model.rememberConversation("opened")
        subscribe(model)
        runCurrent()
        listOf(null, "", " ", "\t\n").forEach { model.rememberConversation(it) }
        history.value = listOf(entry("newer", 200), entry("opened", 1))
        runCurrent()
        assertEquals("opened", model.previousConversationId.value)
        assertEquals("opened", handle.get<String>("previousConversationId"))
    }

    @Test fun `blank IDs cannot create a saved preference and legacy blank preference is ignored`() = runTest(dispatcher) {
        val handle = SavedStateHandle()
        val model = vm(handle)
        model.rememberConversation(null)
        model.rememberConversation(" \t")
        assertNull(handle.get<String>("previousConversationId"))
        history.value = listOf(entry("existing", 10))
        val legacy = vm(SavedStateHandle(mapOf("previousConversationId" to " \t")))
        assertNull(legacy.previousConversationId.value)
        subscribe(legacy)
        runCurrent()
        assertEquals("existing", legacy.previousConversationId.value)
    }

    @Test fun `remembering an ID before its history row arrives keeps an existing fallback`() = runTest(dispatcher) {
        history.value = listOf(entry("existing", 10))
        val model = vm()
        subscribe(model)
        runCurrent()
        model.rememberConversation("incoming")
        runCurrent()
        assertEquals("existing", model.previousConversationId.value)
        history.value = listOf(entry("existing", 10), entry("incoming", 1))
        runCurrent()
        assertEquals("incoming", model.previousConversationId.value)
    }

    @Test fun `navigation always observes unfiltered history regardless of restored page filters`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf(
            "historyQuery" to "not matching", "historyCategory" to "other-category", "historySelected" to arrayListOf("absent"),
        ))
        history.value = listOf(entry("existing", 10))
        val model = vm(handle)
        subscribe(model)
        runCurrent()
        assertEquals("existing", model.previousConversationId.value)
        verify(exactly = 1) { repository.observeHistory("", null, false) }
    }

    @Test fun `history observation stops after five seconds without subscribers and restarts with fresh history`() = runTest(dispatcher) {
        history.value = listOf(entry("before", 10))
        val model = vm()
        assertEquals(0, history.subscriptionCount.value)
        val subscriber = subscribe(model)
        runCurrent()
        assertEquals("before", model.previousConversationId.value)
        subscriber.cancel()
        runCurrent()
        advanceTimeBy(4_999)
        runCurrent()
        assertEquals(1, history.subscriptionCount.value)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(0, history.subscriptionCount.value)
        history.value = listOf(entry("after", 20))
        model.rememberConversation("after")
        subscribe(model)
        runCurrent()
        assertEquals(1, history.subscriptionCount.value)
        assertEquals("after", model.previousConversationId.value)
    }

    @Test fun `no history replaces even a restored saved ID with null after observation`() = runTest(dispatcher) {
        val handle = SavedStateHandle(mapOf("previousConversationId" to "absent"))
        val model = vm(handle)
        assertEquals("absent", model.previousConversationId.value)
        subscribe(model)
        runCurrent()
        assertNull(model.previousConversationId.value)
        assertFalse(handle.get<String>("previousConversationId").isNullOrBlank())
    }
}
