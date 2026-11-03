package com.moge.app.ui.capture

import android.app.Application
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CameraCaptureOverlayTest {
    @Test fun `shutter clears controls before camera capture starts and keeps them cleared on success or failure`() {
        val context = RuntimeEnvironment.getApplication()
        for (fail in listOf(false, true)) {
            val controller = ViewfinderController()
            val capture = mockk<ImageCapture>(relaxed = true)
            val callback = slot<ImageCapture.OnImageSavedCallback>()
            var overlayVisible = true
            var finished = false
            controller.imageCapture = capture
            controller.onCaptureStarted = { overlayVisible = false }
            every { capture.takePicture(any<ImageCapture.OutputFileOptions>(), any(), capture(callback)) } answers {
                assertTrue(controller.busy)
                assertFalse("No exposure control may remain while the shutter is pending", overlayVisible)
            }
            val target = File(context.cacheDir, "capture-controls-$fail.jpg")
            controller.takePicture(context, target, { finished = true }, { finished = true })
            assertTrue(controller.busy)
            assertFalse(overlayVisible)
            if (fail) callback.captured.onError(ImageCaptureException(ImageCapture.ERROR_CAPTURE_FAILED, "test", null))
            else callback.captured.onImageSaved(mockk(relaxed = true))
            assertTrue(finished)
            assertFalse(controller.busy)
            assertFalse("Completing a capture must not resurrect the previous controls", overlayVisible)
        }
    }
}
