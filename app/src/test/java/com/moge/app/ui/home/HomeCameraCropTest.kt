package com.moge.app.ui.home

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.google.common.util.concurrent.SettableFuture
import com.moge.app.ui.theme.MogeTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class HomeCameraCropTest {
    @get:Rule val compose = createComposeRule()
    private val state = MutableStateFlow(HomeUiState())
    private val vm = mockk<HomeViewModel>(relaxed = true)
    private val provider = mockk<ProcessCameraProvider>(relaxed = true)
    private lateinit var future: SettableFuture<ProcessCameraProvider>

    @Before fun setUp() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.CAMERA)
        mockkObject(ProcessCameraProvider.Companion)
        future = SettableFuture.create<ProcessCameraProvider>().apply { set(provider) }
        every { ProcessCameraProvider.getInstance(any()) } answers { future }
        every { provider.bindToLifecycle(any(), any(), any(), any()) } returns mockk<Camera>(relaxed = true)
        every { vm.state } returns state
        every { vm.onRetake() } answers {
            state.value = state.value.copy(cropQueue = state.value.cropQueue.drop(1))
        }
        every { vm.onCropDone() } answers {
            val current = state.value
            val rest = current.cropQueue.drop(1)
            state.value = current.copy(
                photos = current.photos + current.cropQueue.first(), cropQueue = rest, confirmOpen = rest.isEmpty(),
            )
        }
    }

    @After fun tearDown() = unmockkObject(ProcessCameraProvider.Companion)

    private fun photo(): String = File.createTempFile("camera-crop-", ".jpg").apply {
        outputStream().use {
            Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 100, it)
        }
        deleteOnExit()
    }.absolutePath

    private fun mount() {
        compose.setContent { MogeTheme { HomeScreen(onOpenNotebook = {}, onOpenSettings = {}, onAskByText = {}, onStartSolve = {}, vm = vm) } }
        compose.waitForIdle()
    }

    private fun clickText(text: String) = compose.onNodeWithText(text)
        .performSemanticsAction(SemanticsActions.OnClick) { it() }

    @Test fun `capture crop disposes the camera and retake restores shutter and flash controls`() {
        mount()
        compose.onNodeWithContentDescription("打开闪光灯")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.runOnIdle { state.value = state.value.copy(cropQueue = listOf(photo())) }
        compose.onNodeWithText("裁剪照片").assertExists()
        verify(exactly = 1) { provider.unbind(any(), any()) }
        verify(exactly = 1) { provider.bindToLifecycle(any(), any(), any(), any()) }

        clickText("重拍")
        compose.onNodeWithText("裁剪照片").assertDoesNotExist()
        compose.onNodeWithContentDescription("拍照").assertIsEnabled()
        compose.onNodeWithContentDescription("关闭闪光灯").assertExists()
        verify(exactly = 1) { vm.onRetake() }
        verify(exactly = 2) { provider.bindToLifecycle(any(), any(), any(), any()) }
    }

    @Test fun `camera stays closed for the entire crop queue and final original opens confirmation`() {
        state.value = HomeUiState(cropQueue = listOf(photo(), photo()))
        mount()
        verify(exactly = 0) { ProcessCameraProvider.getInstance(any()) }
        clickText("使用原图")
        compose.onNodeWithText("裁剪照片").assertExists()
        verify(exactly = 0) { ProcessCameraProvider.getInstance(any()) }
        clickText("使用原图")
        compose.onNodeWithText("裁剪照片").assertDoesNotExist()
        compose.onNodeWithText("使用照片").assertExists()
        verify(exactly = 2) { vm.onCropDone() }
        verify(exactly = 1) { provider.bindToLifecycle(any(), any(), any(), any()) }
    }

    @Test fun `editing an accepted photo also closes the camera and cancel restores confirmation`() {
        state.value = HomeUiState(photos = listOf(photo()), confirmOpen = true)
        mount()
        compose.onNodeWithContentDescription("裁剪第 1 张照片")
            .performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.onNodeWithText("裁剪照片").assertExists()
        verify(exactly = 1) { provider.unbind(any(), any()) }
        clickText("取消")
        compose.onNodeWithText("裁剪照片").assertDoesNotExist()
        compose.onNodeWithText("使用照片").assertExists()
        verify(exactly = 2) { provider.bindToLifecycle(any(), any(), any(), any()) }
    }

    @Test fun `provider completing during crop cannot reopen the camera before retake`() {
        future = SettableFuture.create()
        mount()
        compose.runOnIdle { state.value = state.value.copy(cropQueue = listOf(photo())) }
        compose.onNodeWithText("裁剪照片").assertExists()
        compose.runOnIdle { future.set(provider) }
        compose.waitForIdle()
        verify(exactly = 0) { provider.bindToLifecycle(any(), any(), any(), any()) }
        clickText("重拍")
        compose.onNodeWithContentDescription("拍照").assertIsEnabled()
        verify(exactly = 1) { provider.bindToLifecycle(any(), any(), any(), any()) }
    }
}
