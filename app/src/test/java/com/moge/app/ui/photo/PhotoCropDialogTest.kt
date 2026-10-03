package com.moge.app.ui.photo

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
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
import java.io.File

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
        compose.onNodeWithText("保存裁剪").assertIsEnabled()
    }

    @Test fun `free crop retains retake original and confirm actions`() {
        val path = photo().absolutePath
        var retakes = 0
        var originals = 0
        compose.setContent {
            MogeTheme {
                PhotoCropDialog(
                    path, onCropped = {}, onDismiss = { retakes++ }, onUseOriginal = { originals++ },
                    dismissLabel = "重拍", confirmLabel = "确定",
                )
            }
        }
        compose.onNodeWithText("确定").assertIsEnabled()
        compose.onNodeWithText("使用原图").performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("重拍").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, originals)
        assertEquals(1, retakes)
    }
}
