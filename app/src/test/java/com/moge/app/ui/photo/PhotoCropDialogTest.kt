package com.moge.app.ui.photo

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.io.File
import kotlinx.coroutines.CompletableDeferred

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PhotoCropDialogTest {
    @get:Rule val compose = createComposeRule()

    private fun photo(): File = File.createTempFile("free-crop-", ".jpg").apply {
        outputStream().use {
            Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 100, it)
        }
        deleteOnExit()
    }

    private fun assertOnlyFreeCrop() {
        compose.onNodeWithText("裁剪照片").assertExists()
        listOf("自由", "1:1", "4:3", "3:4", "16:9", "3:1").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    @Test fun `confirm accepts only after original save callback completes and prevents double submission`() {
        val file = photo()
        val saving = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        var accepted = 0
        var started = 0
        compose.setContent {
            MogeTheme {
                PhotoCropDialog(file.path, onCropped = { accepted++ }, onDismiss = {},
                    onCropStarted = { started++ }, saveCrop = {
                        saving.complete(Unit)
                        releaseSave.await()
                    })
            }
        }
        compose.onNodeWithText("确定").performSemanticsAction(SemanticsActions.OnClick) { click -> click(); click() }
        compose.waitUntil(5_000) { saving.isCompleted }
        compose.onNodeWithText("保存中…").assertIsNotEnabled()
        compose.onNodeWithText("取消").assertIsNotEnabled()
        assertEquals(0, accepted)
        releaseSave.complete(Unit)
        compose.waitUntil(5_000) { accepted == 1 }
        assertEquals(1, started)
    }

    @Test fun `outside touches retain the crop until the cancel button is pressed`() {
        var dismissed = 0
        val file = photo()
        val original = file.readBytes()
        compose.setContent {
            MogeTheme { PhotoCropDialog(file.absolutePath, onCropped = {}, onDismiss = { dismissed++ }) }
        }
        compose.runOnIdle {
            val dialog = ShadowDialog.getLatestDialog()
            // Outside-window events are delivered by Android, rather than a Compose node.
            for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_OUTSIDE)) {
                val event = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(),
                    action, -20f, -20f, 0)
                try { dialog.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            assertEquals(0, dismissed)
        }
        compose.onNodeWithText("确定").assertIsEnabled()
        compose.onNodeWithText("取消").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, dismissed)
        org.junit.Assert.assertArrayEquals(original, file.readBytes())
    }

    @Test fun `only free cropping is offered initially after rotation and for the next photo`() {
        val path = mutableStateOf(photo().absolutePath)
        compose.setContent {
            MogeTheme { PhotoCropDialog(path.value, onCropped = {}, onDismiss = {}) }
        }
        assertOnlyFreeCrop()
        compose.onNodeWithText("逆时针 90°").assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertOnlyFreeCrop()
        compose.runOnIdle { path.value = photo().absolutePath }
        assertOnlyFreeCrop()
        compose.onNodeWithText("确定").assertIsEnabled()
    }

    @Test fun `single confirm accepts the complete original without rewriting it and retains retake`() {
        val file = photo()
        val path = file.absolutePath
        val original = file.readBytes()
        var retakes = 0
        var accepted = 0
        compose.setContent {
            MogeTheme {
                PhotoCropDialog(
                    path, onCropped = { accepted++ }, onDismiss = { retakes++ }, dismissLabel = "重拍",
                )
            }
        }
        compose.onNodeWithText("确定").assertIsEnabled()
        compose.onNodeWithText("使用原图").assertDoesNotExist()
        compose.onNodeWithText("保存裁剪").assertDoesNotExist()
        compose.onNodeWithText("确定").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("重拍").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, accepted)
        org.junit.Assert.assertArrayEquals(original, file.readBytes())
        assertEquals(1, retakes)
    }
}
