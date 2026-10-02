package com.moge.app.ui.capture

import android.app.Application
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.google.common.util.concurrent.SettableFuture
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CameraLifetimeTest {
    @get:Rule val compose = createComposeRule()

    @Before fun mockProvider() = mockkObject(ProcessCameraProvider.Companion)
    @After fun restoreProvider() = unmockkObject(ProcessCameraProvider.Companion)

    @Test
    fun `provider completion after leaving the camera never binds a camera`() {
        val future = SettableFuture.create<ProcessCameraProvider>()
        val provider = mockk<ProcessCameraProvider>(relaxed = true)
        every { ProcessCameraProvider.getInstance(any()) } returns future
        val showCamera = mutableStateOf(true)
        val controller = ViewfinderController()
        compose.setContent {
            if (showCamera.value) CameraViewfinder(controller, Modifier.size(200.dp))
        }
        compose.waitForIdle()
        compose.runOnIdle { showCamera.value = false }
        compose.waitForIdle()
        compose.runOnIdle { future.set(provider) }
        compose.waitForIdle()
        verify { provider wasNot Called }
        assertFalse(controller.ready)
        assertFalse(controller.failed)
    }
}
