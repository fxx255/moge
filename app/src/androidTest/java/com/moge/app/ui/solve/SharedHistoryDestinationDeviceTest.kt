package com.moge.app.ui.solve

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.moge.app.MainActivity
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.DatabaseModule
import com.moge.app.data.db.MessageEntity
import com.moge.app.domain.ConversationMode
import com.moge.app.domain.SolveMode
import com.moge.app.runtime.DraftStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real share -> history selection -> target VM -> crop -> existing draft, with no model request. */
@RunWith(AndroidJUnit4::class)
class SharedHistoryDestinationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun selectingAnOlderChatKeepsItsDraftAndAddsOnlyTheSharedImages() {
        val context = instrumentation.targetContext
        val launchIntent = Intent(compose.activity.intent)
        val db = DatabaseModule.provideDatabase(context)
        val repository = ConversationRepository(db.conversationDao(), Dispatchers.IO)
        val drafts = DraftStore(context)
        val token = UUID.randomUUID().toString().take(8)
        val existingText = "历史草稿-$token"
        val incomingText = "追加图片-$token"
        val old = runBlocking { repository.createConversation("历史目标-$token", SolveMode.entries.first(), ConversationMode.CHAT) }
        val newest = runBlocking { repository.createConversation("较新记录-$token", SolveMode.entries.first(), ConversationMode.CHAT) }
        val photo = File(context.filesDir, "photos/history-share-$token.png").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(160, 220, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.CYAN); photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        runBlocking {
            db.requestDao().insertMessage(MessageEntity(conversationId = old.id, role = "user", content = "旧消息"))
            db.requestDao().insertMessage(MessageEntity(conversationId = newest.id, role = "user", content = "新消息"))
            drafts.persist(drafts.reserveSave(old.id, existingText, listOf(photo.path)))
        }
        try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", photo)
            instrumentation.context.startActivity(Intent(Intent.ACTION_SEND)
                .setComponent(ComponentName(context, MainActivity::class.java)).setType("image/png")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, incomingText))
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("shared-image-panel").fetchSemanticsNodes().isNotEmpty() }
            // The real singleTask receiver replaces its intent. ActivityScenario matches
            // lifecycle events to the original launch intent, including test teardown.
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().forEach { it.intent = Intent(launchIntent) }
            }
            compose.onNodeWithTag("share-history-menu").assertDoesNotExist()
            compose.onNodeWithTag("share-history-toggle").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("share-history-${old.id}").fetchSemanticsNodes().isNotEmpty() }
            val newerBounds = compose.onNodeWithTag("share-history-${newest.id}").fetchSemanticsNode().boundsInRoot
            val olderBounds = compose.onNodeWithTag("share-history-${old.id}").fetchSemanticsNode().boundsInRoot
            assertTrue(newerBounds.top < olderBounds.top)
            screenshot("share-history-menu.png")
            compose.onNodeWithTag("share-history-${old.id}").performClick()
            compose.onNodeWithTag("share-history-menu").assertDoesNotExist()
            compose.onNodeWithTag("share-join-current").performClick()
            compose.waitUntil(15_000) { compose.onAllNodes(hasText("确定") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("裁剪照片").assertIsDisplayed()
            compose.onNodeWithText("确定").performClick()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("shared-image-panel").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("conversation-input") and hasText("$existingText\n$incomingText", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            val saved = runBlocking { drafts.load(old.id)!! }
            assertEquals("$existingText\n$incomingText", saved.text)
            assertEquals(2, saved.photoPaths.size)
            assertEquals(photo.path, saved.photoPaths.first())
            assertEquals(1, runBlocking { db.conversationDao().getMessages(old.id).size })
            assertNull(runBlocking { drafts.load(newest.id) })
            screenshot("share-history-delivered.png")
        } finally {
            runBlocking {
                val added = drafts.load(old.id)?.photoPaths.orEmpty().filterNot { it == photo.path }
                drafts.clearConversation(old.id)
                drafts.clearConversation(newest.id)
                repository.deleteIdle(setOf(old.id, newest.id))
                added.forEach { File(it).delete() }
            }
            photo.delete()
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
}
