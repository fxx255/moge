package com.moge.app.ui.solve

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.MogeTheme
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, qualifiers = "w393dp-h820dp")
class SolveCameraEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val state = MutableStateFlow(SolveUiState(conversationMode = ConversationMode.CHAT, input = "保留这段草稿"))
    private val vm = mockk<SolveViewModel>(relaxed = true)
    private var resolvedMode = ConversationMode.CHAT
    private var questionCameraEntries = 0
    private lateinit var target: File
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Before fun setup() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        app.getSharedPreferences("capture_permissions", 0).edit().clear().commit()
        target = File(app.filesDir, "photos/direct-chat.jpg").apply { parentFile!!.mkdirs(); delete() }
        // FileProvider's Android '/' paths cannot match Robolectric's Windows file paths.
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns
            Uri.parse("content://${app.packageName}.files/photos/${target.name}")
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply { packageName = "test.camera"; name = "test.camera.CaptureActivity"; exported = true }
        })
        mockkObject(ProcessCameraProvider.Companion)
        every { vm.uiState } returns state
        every { vm.newCameraFile() } returns target
        every { vm.resolveConversationMode(any()) } answers { firstArg<(ConversationMode) -> Unit>()(resolvedMode) }
        every { vm.addPhotos(any()) } answers { state.value = state.value.copy(photos = state.value.photos + firstArg<List<String>>()) }
        every { vm.discardPhoto(any()) } answers { File(firstArg<String>()).delete(); Unit }
        coEvery { vm.savePhotoCrop(any(), any()) } returns Unit
    }

    @After fun cleanup() {
        unmockkObject(ProcessCameraProvider.Companion)
        unmockkStatic(FileProvider::class)
        target.delete()
    }

    private fun mount() {
        restoration.setContent { MogeTheme {
            SolveScreen(onTakePhoto = { questionCameraEntries++ }, vm = vm)
        } }
        compose.waitForIdle()
    }

    private fun takePhoto() {
        compose.onNodeWithContentDescription("添加附件").performClick()
        compose.onNodeWithContentDescription("拍照").performClick()
        compose.waitForIdle()
    }

    private fun photoResult() {
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try { target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) } }
        finally { bitmap.recycle() }
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, null) }
        compose.onNodeWithText("裁剪照片").assertExists()
    }

    @Test fun `chat opens the system camera over the unchanged conversation without a capture page`() {
        mount()
        val before = compose.onNodeWithTag("conversation-input").fetchSemanticsNode().boundsInRoot
        takePhoto()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.intent.action)
        assertEquals(0, questionCameraEntries)
        compose.onNodeWithTag("conversation-input").assertTextContains("保留这段草稿")
        assertEquals(before, compose.onNodeWithTag("conversation-input").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("camera-preview").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开闪光灯").assertDoesNotExist()
        takePhoto()
        assertNull("A second tap must not start another camera", shadowOf(compose.activity).nextStartedActivityForResult)
        verify(exactly = 1) { vm.newCameraFile() }
        verify(exactly = 0) { ProcessCameraProvider.getInstance(any()) }
    }

    @Test fun `question mode keeps the existing embedded capture route`() {
        state.value = state.value.copy(conversationMode = ConversationMode.SOLVE)
        resolvedMode = ConversationMode.SOLVE
        mount()
        takePhoto()
        assertEquals(1, questionCameraEntries)
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        verify(exactly = 0) { vm.newCameraFile() }
    }

    @Test fun `resolved chat mode takes precedence over an early solving UI snapshot`() {
        state.value = state.value.copy(conversationMode = ConversationMode.SOLVE, draftLoaded = false)
        mount()
        takePhoto()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        assertEquals(0, questionCameraEntries)
    }

    @Test fun `cancellation keeps the original draft and removes the incomplete shot`() {
        mount()
        takePhoto()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        target.writeBytes(byteArrayOf(1))
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null) }
        compose.onNodeWithTag("conversation-input").assertTextContains("保留这段草稿")
        assertEquals(0, questionCameraEntries)
        assertTrue(state.value.photos.isEmpty())
        assertFalse(target.exists())
        verify(exactly = 0) { vm.addPhotos(any()) }
    }

    @Test fun `accepting a cropped system photo adds one attachment without sending or navigating`() {
        state.value = state.value.copy(photos = listOf("accepted.jpg"))
        mount()
        takePhoto()
        photoResult()
        compose.onNodeWithText("确定").performClick()
        compose.waitUntil(5_000) { state.value.photos.size == 2 }
        compose.onNodeWithText("裁剪照片").assertDoesNotExist()
        assertEquals(listOf("accepted.jpg", target.absolutePath), state.value.photos)
        assertEquals("保留这段草稿", state.value.input)
        assertEquals(0, questionCameraEntries)
        verify(exactly = 0) { vm.send() }
        coVerify(exactly = 1) { vm.savePhotoCrop(target.absolutePath, any()) }
    }

    @Test fun `retake opens the system camera directly after discarding the cropped shot`() {
        mount()
        takePhoto()
        photoResult()
        compose.onNodeWithText("重拍").performClick()
        compose.onNodeWithText("裁剪照片").assertDoesNotExist()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        assertEquals(0, questionCameraEntries)
        verify(exactly = 1) { vm.discardPhoto(target.absolutePath) }
        verify(exactly = 0) { ProcessCameraProvider.getInstance(any()) }
    }

    @Test fun `restoring an in flight system camera retains its result destination and original draft`() {
        mount()
        takePhoto()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null) }
        compose.onNodeWithTag("conversation-input").assertTextContains("保留这段草稿")
        verify(exactly = 1) { vm.newCameraFile() }
        assertEquals(0, questionCameraEntries)
    }

    @Test fun `granting permission launches directly from chat without navigating`() {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        mount()
        takePhoto()
        val request = shadowOf(compose.activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.CAMERA), request.requestedPermissions)
        assertEquals(0, questionCameraEntries)
        verify(exactly = 0) { vm.newCameraFile() }
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        compose.runOnIdle {
            compose.activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
                Intent().putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS, arrayOf(Manifest.permission.CAMERA))
                    .putExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS, intArrayOf(PackageManager.PERMISSION_GRANTED)))
        }
        compose.waitForIdle()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        assertEquals(0, questionCameraEntries)
    }

    @Test fun `returning from settings without permission allows another attempt and keeps chat visible`() {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        app.getSharedPreferences("capture_permissions", 0).edit().putBoolean("camera_requested", true).commit()
        mount()
        takePhoto()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, request.intent.action)
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null) }
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        compose.onNodeWithTag("conversation-input").assertTextContains("保留这段草稿")
        takePhoto()
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        assertEquals(0, questionCameraEntries)
        verify(exactly = 0) { vm.newCameraFile() }
    }
}
