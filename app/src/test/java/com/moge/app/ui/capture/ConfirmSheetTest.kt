package com.moge.app.ui.capture

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ConfirmSheetTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `use photos only invokes the handoff and requires no model configuration`() {
        var handoffs = 0
        compose.setContent {
            MogeTheme {
                ConfirmSheet(
                    photos = listOf("/photo.jpg"), note = "", canAddMore = true,
                    onNoteChange = {}, onRemove = {}, onOpenPhoto = {}, onAddMore = {},
                    onStart = { handoffs++ }, onDismiss = {},
                )
            }
        }
        // ModalBottomSheet 使用独立窗口；语义动作同时验证其无障碍点击回调。
        compose.onNodeWithText("使用照片").assertIsEnabled().performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, handoffs)
        compose.onNodeWithText("开始解题").assertDoesNotExist()
        compose.onNodeWithText("举一反三").assertDoesNotExist()
        compose.onNodeWithText("帮我查错").assertDoesNotExist()
    }

    @Test
    fun `confirmation retains crop remove and continue adding actions`() {
        var cropped = -1
        var removed: String? = null
        var additions = 0
        compose.setContent {
            MogeTheme {
                ConfirmSheet(
                    photos = listOf("/photo.jpg"), note = "", canAddMore = true,
                    onNoteChange = {}, onRemove = { removed = it }, onOpenPhoto = {},
                    onCropPhoto = { cropped = it }, onAddMore = { additions++ },
                    onStart = {}, onDismiss = {},
                )
            }
        }
        compose.onNodeWithContentDescription("裁剪第 1 张照片").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(0, cropped)
        compose.onNodeWithContentDescription("删掉第 1 张照片").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals("/photo.jpg", removed)
        compose.onNodeWithText("继续添加").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(1, additions)
    }
}
