package com.moge.app.ui.home

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiModelProfile
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.SolveMode
import com.moge.app.ui.capture.CaptureStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** 取景页：连拍 → 逐张裁剪 → 确认面板 → 交回待发附件；设置偏好与照片状态恢复。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class HomeViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var store: CaptureStore
    private val credentials = mockk<AiCredentialStore>(relaxed = true)
    private val preferences = MutableStateFlow(UserSettings(defaultSolveMode = SolveMode.CHECK_WORK))
    private val settings = mockk<SettingsRepository> {
        every { settings } returns preferences
    }

    private fun profile(id: String, vision: Boolean) = AiModelProfile(
        id = id, name = "模型$id", baseUrl = "https://x", model = "m$id", visionEnabled = vision,
        searchProtocol = AiSearchProtocol.OFF, reasoningEffort = AiReasoningEffort.LOW, hasApiKey = true,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = CaptureStore(ApplicationProvider.getApplicationContext<Context>())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm(handle: SavedStateHandle = SavedStateHandle()) =
        HomeViewModel(handle, credentials, settings, store, Dispatchers.Unconfined)

    private fun shot(): String = store.newCameraFile().apply { writeBytes(byteArrayOf(9)) }.absolutePath

    private fun HomeViewModel.await(predicate: (HomeUiState) -> Boolean): HomeUiState = runBlocking {
        withTimeout(5_000) { while (!predicate(state.value)) delay(10) }
        state.value
    }

    @Test
    fun `default mode comes from settings`() {
        assertEquals(SolveMode.CHECK_WORK, vm().await { it.modelsLoaded }.mode)
    }

    @Test
    fun `no usable model shows the add model banner`() {
        every { credentials.activeProfile() } returns null
        val state = vm().await { it.modelsLoaded }
        assertNull(state.chip)
    }

    @Test
    fun `chip shows recognizer only when primary cannot read photos`() {
        every { credentials.activeProfile() } returns profile("a", vision = false)
        every { credentials.questionVisionProfileId() } returns "v"
        every { credentials.profiles() } returns listOf(profile("a", false), profile("v", true))
        val chip = vm().await { it.modelsLoaded }.chip!!
        assertEquals("模型a", chip.primary)
        assertEquals("模型v", chip.recognizer)
        assertTrue(chip.canReadPhotos)

        every { credentials.questionVisionProfileId() } returns null
        val alone = vm().await { it.modelsLoaded }.chip!!
        assertNull(alone.recognizer)
        assertFalse("主模型不看图又没识题模型", alone.canReadPhotos)
    }

    @Test
    fun `burst shots queue for cropping and open confirm after the last one`() {
        val vm = vm()
        val a = shot()
        val b = shot()
        vm.onCaptured(a)
        vm.onCaptured(b)
        assertEquals(listOf(a, b), vm.state.value.cropQueue)

        vm.onCropDone()
        assertEquals(listOf(a), vm.state.value.photos)
        assertFalse("队列没空不弹确认面板", vm.state.value.confirmOpen)

        vm.onCropDone()
        assertEquals(listOf(a, b), vm.state.value.photos)
        assertTrue(vm.state.value.confirmOpen)
    }

    @Test
    fun `retake drops the photo file`() {
        val vm = vm()
        val a = shot()
        vm.onCaptured(a)
        vm.onRetake()
        assertTrue(vm.state.value.cropQueue.isEmpty())
        runBlocking { withTimeout(2_000) { while (File(a).exists()) delay(10) } }
    }

    @Test
    fun `ninth photo is the last one accepted`() {
        val vm = vm()
        repeat(CaptureStore.MAX_PHOTOS) { vm.onCaptured(shot()) }
        val extra = shot()
        vm.onCaptured(extra)
        assertEquals(CaptureStore.MAX_PHOTOS, vm.state.value.count)
        assertFalse(File(extra).exists())
        assertTrue(vm.state.value.message!!.contains("最多"))
    }

    @Test
    fun `use photos hands off settings mode and note and clears the session without deleting files`() {
        val vm = vm()
        val a = shot()
        vm.onCaptured(a)
        vm.onCropDone()
        vm.setNote("  第 3 步  ")
        val batch = vm.takeBatch()!!
        assertEquals(listOf(a), batch.photoPaths)
        assertEquals(SolveMode.CHECK_WORK, batch.solveMode)
        assertEquals("第 3 步", batch.note)
        assertTrue("交出去的照片不能删", File(a).exists())
        assertEquals(0, vm.state.value.count)
        assertNull(vm.takeBatch())
    }

    @Test
    fun `session survives recreation through saved state`() {
        val handle = SavedStateHandle()
        val first = vm(handle)
        val a = shot()
        first.onCaptured(a)
        first.onCropDone()
        val b = shot()
        first.onCaptured(b)
        first.setNote("保留说明")

        val again = vm(handle).await { it.modelsLoaded }
        assertEquals(listOf(a), again.photos)
        assertEquals(listOf(b), again.cropQueue)
        assertEquals("保留说明", again.note)
        assertTrue(again.confirmOpen)
        assertEquals(SolveMode.CHECK_WORK, again.mode)
    }

    @Test
    fun `settings changes apply to an existing batch and override old saved capture modes`() {
        val vm = vm(SavedStateHandle(mapOf("capture_mode" to "VARIANTS")))
        val a = shot()
        vm.onCaptured(a)
        vm.onCropDone()
        preferences.value = UserSettings(defaultSolveMode = SolveMode.DETAILED)
        assertEquals(SolveMode.DETAILED, vm.await { it.mode == SolveMode.DETAILED }.mode)
        assertEquals(SolveMode.DETAILED, vm.takeBatch()!!.solveMode)
    }

    @Test
    fun `cannot hand off a batch while another photo is waiting for cropping`() {
        val vm = vm()
        val a = shot()
        val b = shot()
        vm.onCaptured(a)
        vm.onCropDone()
        vm.onCaptured(b)
        assertNull(vm.takeBatch())
        assertEquals(listOf(a), vm.state.value.photos)
        assertEquals(listOf(b), vm.state.value.cropQueue)
    }

    @Test
    fun `continuing to add photos preserves the current selection and note`() {
        val vm = vm()
        val a = shot()
        vm.onCaptured(a)
        vm.onCropDone()
        vm.setNote("待发说明")
        vm.closeConfirm()
        assertFalse(vm.state.value.confirmOpen)
        assertEquals(listOf(a), vm.state.value.photos)
        assertEquals("待发说明", vm.state.value.note)
        vm.openConfirm()
        assertTrue(vm.state.value.confirmOpen)
    }
}
