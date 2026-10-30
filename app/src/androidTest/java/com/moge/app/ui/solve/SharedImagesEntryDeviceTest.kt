package com.moge.app.ui.solve

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.moge.app.MainActivity
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real SEND intents exercise manifest discovery, singleTask entry, import, choice and live drafts. */
@RunWith(AndroidJUnit4::class)
class SharedImagesEntryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun externalImageSharesOfferNewChatAndCurrentDraftAndSurviveActivityRecreation() {
        val context = instrumentation.targetContext
        val launchedActivity = compose.activity
        val launchIntent = Intent(launchedActivity.intent)
        val settings = SettingsRepository(context)
        val appearance = InstrumentationRegistry.getArguments().getString("shareAppearance")?.let(Appearance::valueOf)
        val originalAppearance = appearance?.let { requested ->
            runBlocking { settings.current().appearance.also { settings.setAppearance(requested) } }
        }
        val handlers = context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_SEND).setType("image/jpeg"), PackageManager.MATCH_DEFAULT_ONLY)
        assertTrue("墨格 must be offered by the system image share sheet", handlers.any {
            it.activityInfo.packageName == context.packageName && it.activityInfo.name == MainActivity::class.java.name
        })
        val files = (1..3).map { index ->
            File(context.filesDir, "photos/device-share-$index.png").also { file ->
                file.parentFile!!.mkdirs()
                val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(if (index % 2 == 0) Color.YELLOW else Color.CYAN)
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
        }
        fun share(photos: List<File>, note: String) {
            val uris = photos.map { FileProvider.getUriForFile(context, context.packageName + ".files", it) }
            val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
                .setComponent(ComponentName(context, MainActivity::class.java)).setType("image/png")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_TEXT, note)
            if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.single())
            else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            intent.clipData = ClipData.newRawUri("照片", uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            instrumentation.context.startActivity(intent)
            compose.waitUntil(15_000) { compose.onAllNodesWithText("添加分享图片").fetchSemanticsNodes().isNotEmpty() }
            // ActivityScenario matches lifecycle events against its original launch intent.
            // singleTask onNewIntent legitimately replaces it; restore the harness identity
            // after the real receiver has consumed and copied this share.
            instrumentation.runOnMainSync { launchedActivity.intent = Intent(launchIntent) }
        }
        try {
            share(listOf(files[0]), "分享测试首张")
            assertPanelAboveComposer()
            selectDestination("新对话")
            compose.onNodeWithText("对话", substring = false).performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasText("对话") and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("对话", substring = false).assertIsSelected()
            snapshot("share-images-new-chat.png")
            compose.onNodeWithText("添加到输入框").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("添加分享图片").fetchSemanticsNodes().isEmpty() }
            waitForInput("分享测试首张")
            compose.onNodeWithTag("conversation-input").assertTextContains("分享测试首张")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("待发的第 1 张照片").fetchSemanticsNodes().size == 1
            }
            share(files.drop(1), "两张追加图片")
            // Recreate through Android directly: ActivityScenario's cached PAUSED state
            // predates the singleTask share's onNewIntent / onResume identity change.
            instrumentation.runOnMainSync { launchedActivity.recreate() }
            compose.waitUntil(15_000) {
                var resumed = false
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .any { it is MainActivity && it !== launchedActivity }
                }
                resumed
            }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("添加分享图片").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("share-destination-picker").performScrollTo().performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodes(destination("当前对话") and isEnabled()).fetchSemanticsNodes().size == 1
            }
            compose.onNode(destination("当前对话")).performScrollTo().performClick()
            try {
                compose.waitUntil(15_000) { compose.onAllNodesWithText("当前类型：对话").fetchSemanticsNodes().isNotEmpty() }
            } catch (error: Throwable) {
                snapshot("share-images-recreation-failure.png")
                throw AssertionError(compose.onRoot().printToString(), error)
            }
            assertPanelAboveComposer()
            snapshot("share-images-current-chat.png")
            compose.onNodeWithText("添加到输入框").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("添加分享图片").fetchSemanticsNodes().isEmpty() }
            waitForInput("分享测试首张\n两张追加图片")
            compose.onNodeWithTag("conversation-input").assertTextContains("分享测试首张\n两张追加图片")
            compose.waitUntil(10_000) {
                compose.onAllNodesWithContentDescription("待发的第 3 张照片").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription("待发的第 4 张照片").assertDoesNotExist()
            snapshot("share-images-draft.png")
            // No model request is needed to inspect or add the shared attachments.
            compose.onNodeWithContentDescription("停止生成").assertDoesNotExist()
        } finally {
            files.forEach(File::delete)
            originalAppearance?.let { runBlocking { settings.setAppearance(it) } }
        }
    }

    private fun destination(label: String) = hasText(label) and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    private fun selectDestination(label: String) {
        compose.onNodeWithTag("share-destination-picker").performScrollTo().performClick()
        compose.onNode(destination(label)).performScrollTo().performClick()
    }

    private fun assertPanelAboveComposer() {
        val panel = compose.onNodeWithTag("shared-image-panel").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot
        assertTrue("The attachment panel must stay above the actual input area", panel.bottom <= input.top)
        compose.onNodeWithTag("conversation-input").assertIsDisplayed()
        compose.onNodeWithTag("share-add-to-input").assertIsDisplayed()
        val picker = compose.onNodeWithTag("share-destination-picker").getUnclippedBoundsInRoot()
        val confirm = compose.onNodeWithTag("share-add-to-input").getUnclippedBoundsInRoot()
        assertTrue("The destination must be fully above the confirm action", picker.bottom <= confirm.top)
    }

    private fun waitForInput(text: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("conversation-input") and hasText(text, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun snapshot(name: String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try { File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream()
                .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }
}
