package com.moge.app.ui.share

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import com.moge.app.runtime.SharedImages
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SharedImageCropSequenceTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun batch() = SharedImages("shared-batch", (1..2).map {
        temporary.newFile("photo-$it.png").also { file ->
            val bitmap = Bitmap.createBitmap(120, 180, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }.absolutePath
    })

    @Test fun confirmsEveryPhotoInOrderAndRestoresTheNextPhotoBeforeDelivery() {
        val share = batch()
        val saved = CopyOnWriteArrayList<String>()
        val started = CopyOnWriteArrayList<String>()
        var delivered = 0
        var editing by mutableStateOf(true)
        val restore = StateRestorationTester(compose)
        restore.setContent { MogeTheme {
            if (editing) SharedImageCropSequence(share,
                onComplete = { delivered++; editing = false }, onDismiss = { editing = false },
                onCropStarted = { started += it }, onCropClosed = {},
                saveCrop = { path, _ -> saved += path })
        } }
        compose.onNodeWithText("确定").performClick()
        compose.waitUntil(5_000) { started.lastOrNull() == share.photoPaths.last() }
        compose.runOnIdle { assertEquals(0, delivered) }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("裁剪照片").assertIsDisplayed()
        compose.onNodeWithText("确定").performClick()
        compose.waitUntil(5_000) { delivered == 1 }
        assertEquals(share.photoPaths, saved.toList())
        compose.onNodeWithText("裁剪照片").assertDoesNotExist()
    }

    @Test fun cancellingTheSecondPhotoDoesNotDeliverAPartialBatch() {
        val share = batch()
        val started = CopyOnWriteArrayList<String>()
        var delivered = 0
        var cancelled = 0
        var editing by mutableStateOf(true)
        compose.setContent { MogeTheme {
            if (editing) SharedImageCropSequence(share,
                onComplete = { delivered++ }, onDismiss = { cancelled++; editing = false },
                onCropStarted = { started += it }, onCropClosed = {}, saveCrop = { _, _ -> })
        } }
        compose.onNodeWithText("确定").performClick()
        compose.waitUntil(5_000) { started.lastOrNull() == share.photoPaths.last() }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, delivered); assertEquals(1, cancelled) }
        compose.onNodeWithText("裁剪照片").assertDoesNotExist()
    }

    @Test fun failedSaveKeepsTheSamePhotoAvailableForRetry() {
        val share = batch()
        val started = CopyOnWriteArrayList<String>()
        val saved = CopyOnWriteArrayList<String>()
        var fail = true
        var delivered = 0
        compose.setContent { MogeTheme {
            SharedImageCropSequence(share, onComplete = { delivered++ }, onDismiss = {},
                onCropStarted = { started += it }, onCropClosed = {}, saveCrop = { path, _ ->
                    if (fail) { fail = false; error("磁盘写入失败") }
                    saved += path
                })
        } }
        compose.onNodeWithText("确定").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("磁盘写入失败").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(share.photoPaths.first()), started.toList())
        assertEquals(0, delivered)
        compose.onNodeWithText("确定").performClick()
        compose.waitUntil(5_000) { started.lastOrNull() == share.photoPaths.last() }
        compose.onNodeWithText("确定").performClick()
        compose.waitUntil(5_000) { delivered == 1 }
        assertEquals(share.photoPaths, saved.toList())
    }
}
