package com.moge.app.ui.home

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
import androidx.activity.ComponentActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.capture.CaptureStore
import com.moge.app.ui.theme.MogeTheme
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
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
@Config(sdk = [33], application = Application::class)
class HomeSystemCameraTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restoration = StateRestorationTester(compose)
    private val state = MutableStateFlow(HomeUiState(conversationMode = ConversationMode.CHAT))
    private val vm = mockk<HomeViewModel>(relaxed = true)
    private lateinit var target: File
    private var backs = 0
    private val messages = mutableListOf<String>()

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        app.getSharedPreferences("capture_permissions", 0).edit().clear().commit()
        target = File(app.filesDir, "photos/system-chat.jpg").apply { parentFile!!.mkdirs(); delete() }
        // AndroidX FileProvider assumes '/' paths, while Robolectric uses the host's
        // Windows paths. Only replace that platform boundary; exercise the real launcher.
        mockkStatic(FileProvider::class)
        every { FileProvider.getUriForFile(any(), any(), any()) } returns
            Uri.parse("content://${app.packageName}.files/photos/${target.name}")
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(MediaStore.ACTION_IMAGE_CAPTURE), ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = "test.camera"
                name = "test.camera.CaptureActivity"
                exported = true
            }
        })
        mockkObject(ProcessCameraProvider.Companion)
        every { vm.state } returns state
        every { vm.newCameraFile() } returns target
        every { vm.onCaptured(any()) } answers { state.value = state.value.copy(cropQueue = listOf(firstArg())) }
        every { vm.onRetake() } answers { state.value = state.value.copy(cropQueue = state.value.cropQueue.drop(1)) }
        every { vm.openConfirm() } answers { state.value = state.value.copy(confirmOpen = true) }
        every { vm.showMessage(any()) } answers { messages += firstArg<String>() }
    }

    @After fun cleanup() {
        unmockkObject(ProcessCameraProvider.Companion)
        unmockkStatic(FileProvider::class)
        target.delete()
    }

    private fun mount() {
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.CAMERA))
        restoration.setContent { MogeTheme {
            HomeScreen({}, {}, { backs++ }, {}, vm)
        } }
        compose.waitForIdle()
    }

    @Test fun `chat opens the system camera once without binding CameraX or showing paper controls`() {
        mount()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("Chat must launch the system camera directly; messages=$messages", request)
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, request.intent.action)
        assertEquals(Uri.parse("content://${compose.activity.packageName}.files/photos/${target.name}"),
            request.intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java))
        verify(exactly = 1) { FileProvider.getUriForFile(any(), "${compose.activity.packageName}.files", target) }
        compose.onNodeWithContentDescription("打开闪光灯").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开水平仪").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(note = "保留当前对话") }
        assertNull("Recomposition must not reopen the camera", shadowOf(compose.activity).nextStartedActivityForResult)
        verify(exactly = 0) { ProcessCameraProvider.getInstance(any()) }
    }

    @Test fun `cancelling the system camera returns to chat and removes the unfinished photo`() {
        mount()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        target.writeBytes(byteArrayOf(1))
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null) }
        assertEquals(1, backs)
        assertFalse(target.exists())
        verify(exactly = 0) { vm.onCaptured(any()) }
        verify(exactly = 0) { ProcessCameraProvider.getInstance(any()) }
    }

    @Test fun `system photo enters the existing crop flow and retake launches the system camera again`() {
        mount()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try { target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) } }
        finally { bitmap.recycle() }
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_OK, null) }
        compose.onNodeWithText("裁剪照片").assertExists()
        verify(exactly = 1) { vm.onCaptured(target.absolutePath) }
        compose.onNodeWithText("重拍").performClick()
        compose.onNodeWithText("裁剪照片").assertDoesNotExist()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        assertEquals(0, backs)
        verify(exactly = 0) { ProcessCameraProvider.getInstance(any()) }
    }

    @Test fun `retaking the ninth photo opens the system camera after releasing its slot`() {
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try { target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it) } }
        finally { bitmap.recycle() }
        state.value = state.value.copy(
            photos = List(CaptureStore.MAX_PHOTOS - 1) { "accepted-$it.jpg" },
            cropQueue = listOf(target.absolutePath),
        )
        mount()
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        compose.onNodeWithText("重拍").performClick()
        compose.waitForIdle()
        assertEquals(MediaStore.ACTION_IMAGE_CAPTURE, shadowOf(compose.activity).nextStartedActivityForResult.intent.action)
        assertEquals(CaptureStore.MAX_PHOTOS - 1, state.value.count)
        verify(exactly = 0) { vm.showMessage(any()) }
        verify(exactly = 0) { ProcessCameraProvider.getInstance(any()) }
    }

    @Test fun `cancelling another shot keeps accepted photos available for confirmation`() {
        mount()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        compose.runOnIdle { state.value = state.value.copy(photos = listOf("accepted.jpg")) }
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null) }
        compose.onNodeWithText("使用照片").assertExists()
        assertEquals(listOf("accepted.jpg"), state.value.photos)
        assertEquals(0, backs)
        verify(exactly = 1) { vm.openConfirm() }
    }

    @Test fun `restoring an in flight shot keeps its destination without launching another camera`() {
        mount()
        val request = shadowOf(compose.activity).nextStartedActivityForResult
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
        target.writeBytes(byteArrayOf(1))
        compose.runOnIdle { shadowOf(compose.activity).receiveResult(request.intent, Activity.RESULT_CANCELED, null) }
        assertEquals(1, backs)
        assertFalse(target.exists())
        verify(exactly = 1) { vm.newCameraFile() }
        verify(exactly = 0) { ProcessCameraProvider.getInstance(any()) }
    }
}
