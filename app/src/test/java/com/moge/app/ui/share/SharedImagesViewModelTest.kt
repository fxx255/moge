package com.moge.app.ui.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import com.moge.app.domain.ConversationMode
import com.moge.app.runtime.SharedImageImporter
import com.moge.app.runtime.SharedImages
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SharedImagesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun rotationAndProcessRestoreDoNotImportTwiceAndFailedDeliveryRetainsBatch() = runTest(dispatcher) {
        val importer = mockk<SharedImageImporter>()
        every { importer.imageUris(any()) } answers {
            if (firstArg<Intent>().action == Intent.ACTION_SEND) listOf(Uri.parse("content://images/one")) else emptyList()
        }
        val copied = CompletableDeferred<SharedImages?>()
        coEvery { importer.import(any()) } coAnswers { copied.await() }
        val handle = SavedStateHandle()
        val vm = SharedImagesViewModel(handle, importer)
        val source = Intent(Intent.ACTION_SEND).setType("image/png")
        assertTrue(vm.receive(source))
        val recreatedIntent = Intent(source)
        assertTrue(vm.receive(recreatedIntent))
        dispatcher.scheduler.runCurrent()
        copied.complete(SharedImages("generated", listOf("/private/image.png"), "说明"))
        dispatcher.scheduler.runCurrent()
        coVerify(exactly = 1) { importer.import(any()) }
        assertEquals(1, vm.pending.value.size)
        val restored = SharedImagesViewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }), importer)
        assertTrue(restored.receive(recreatedIntent))
        dispatcher.scheduler.runCurrent()
        assertEquals(vm.pending.value, restored.pending.value)
        coVerify(exactly = 1) { importer.import(any()) }
        restored.deliver(null, current = { error("目标输入已满") })
        dispatcher.scheduler.runCurrent()
        assertEquals("目标输入已满", restored.error.value)
        assertEquals(1, restored.pending.value.size)
        var accepted = 0
        restored.deliver(null, current = { accepted++ })
        restored.deliver(null, current = { accepted++ })
        dispatcher.scheduler.runCurrent()
        assertEquals(1, accepted)
        assertTrue(restored.pending.value.isEmpty())
    }

    @Test fun newDraftNavigationSurvivesRecreationAndKeepsSecondShareQueued() = runTest(dispatcher) {
        val importer = mockk<SharedImageImporter>()
        every { importer.imageUris(any()) } returns listOf(Uri.parse("content://images/one"))
        coEvery { importer.import(any()) } returns SharedImages("generated", listOf("/private/image.png"))
        coEvery { importer.createDraft(any(), ConversationMode.CHAT) } returns "new-chat-id"
        val handle = SavedStateHandle()
        val vm = SharedImagesViewModel(handle, importer)
        repeat(2) { vm.receive(Intent(Intent.ACTION_SEND).setType("image/png")) }
        dispatcher.scheduler.runCurrent()
        assertEquals(2, vm.pending.value.size)
        vm.deliver(ConversationMode.CHAT, current = { fail("New chat must not change current input") })
        dispatcher.scheduler.runCurrent()
        assertEquals("new-chat-id", vm.destination.value)
        assertEquals(1, vm.pending.value.size)
        val restored = SharedImagesViewModel(SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) }), importer)
        assertEquals("new-chat-id", restored.destination.value)
        assertEquals(vm.pending.value, restored.pending.value)
        restored.destinationOpened()
        assertNull(restored.destination.value)
        coEvery { importer.discard(any()) } returns Unit
        restored.dismiss()
        dispatcher.scheduler.runCurrent()
        assertTrue(restored.pending.value.isEmpty())
    }
}
