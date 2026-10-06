package com.moge.app.runtime

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, shadows = [PosixAtomicFileShadow::class])
class BranchDraftStoreTest {
    @Test fun clearingConversationRemovesEveryDraftSlotAndRetainsSharedAttachmentsAndOtherConversations() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        val other = UUID.randomUUID().toString()
        val file = File(context.filesDir, "shared-" + id + ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val store = DraftStore(context)
        val slots = listOf(id, id + "__editing", id + "__edit__q", id + "__path__a")
        slots.forEach { store.save(it, "保留草稿", listOf(file.absolutePath)) }
        store.save(other, "其他会话", listOf(file.absolutePath))
        val fresh = DraftStore(context)
        assertTrue(file.absolutePath in fresh.referencedPhotoPaths())
        fresh.clearConversation(id)
        slots.forEach { assertNull(fresh.load(it)) }
        assertEquals("其他会话", fresh.load(other)!!.text)
        assertTrue(file.isFile)
        assertTrue(file.absolutePath in fresh.referencedPhotoPaths())
        fresh.clearConversation(other)
        file.delete()
        Unit
    }
}
