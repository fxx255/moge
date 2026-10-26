package com.moge.app.ui.capture

import android.Manifest
import android.graphics.BitmapFactory
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.moge.app.ui.theme.MogeTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Real CameraX zoom state, native two-pointer events and a photo taken at the zoom limit. */
@RunWith(AndroidJUnit4::class)
class CameraPinchZoomDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun findPreview(view: View = compose.activity.window.decorView): PreviewView? = when (view) {
        is PreviewView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findPreview(view.getChildAt(it)) }
        else -> null
    }

    @Test fun pinchZoomClampsFromOneToThreeAndKeepsTapFocusAndCaptureWorking() {
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        val controller = ViewfinderController()
        compose.setContent { MogeTheme {
            CameraViewfinder(controller, Modifier.fillMaxSize().background(Color.Black))
        } }
        fun waitForPreview() = compose.waitUntil(30_000) {
            var streaming = false
            compose.runOnUiThread { streaming = findPreview()?.previewStreamState?.value == PreviewView.StreamState.STREAMING }
            streaming || controller.failed
        }
        waitForPreview()
        assertTrue(controller.ready && !controller.failed)
        val info = ProcessCameraProvider.getInstance(context).get(5, TimeUnit.SECONDS)
            .getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
        fun waitForRatio(expected: Float) {
            var ratio = 0f
            try {
                compose.waitUntil(10_000) {
                    compose.runOnUiThread { ratio = info.zoomState.value?.zoomRatio ?: 0f }
                    kotlin.math.abs(ratio - expected) < .005f
                }
            } catch (timeout: ComposeTimeoutException) {
                throw AssertionError("Expected zoom $expected, actual $ratio", timeout)
            }
        }
        val preview = compose.onNodeWithTag("camera-preview")
        fun pinch(enlarge: Boolean) {
            // The platform ignores spans below a physical minimum. Repeated full
            // pinches reach either limit regardless of a device's span threshold.
            repeat(4) {
                preview.performTouchInput {
                    val start = if (enlarge) .04f else .4f
                    val end = if (enlarge) .4f else .04f
                    down(0, center - Offset(width * start, 0f))
                    down(1, center + Offset(width * start, 0f))
                    repeat(16) { step ->
                        val distance = width * (start + (end - start) * (step + 1) / 16f)
                        updatePointerTo(0, center - Offset(distance, 0f))
                        updatePointerTo(1, center + Offset(distance, 0f))
                        move(delayMillis = 24)
                    }
                    up(1)
                    up(0)
                }
            }
        }
        waitForRatio(1f)
        pinch(true)
        waitForRatio(3f)
        compose.onNodeWithTag("camera-focus-indicator").assertDoesNotExist()
        preview.performTouchInput { click(center) }
        compose.onNodeWithTag("camera-focus-indicator").assertExists()
        pinch(false)
        waitForRatio(1f)
        compose.onNodeWithTag("camera-focus-indicator").assertDoesNotExist()
        pinch(true)
        waitForRatio(3f)

        val photo = File(context.cacheDir, "pinch-zoom-capture.jpg")
        var saved = false
        var error: String? = null
        try {
            compose.runOnUiThread { controller.takePicture(context, photo, { saved = true }, { error = it }) }
            compose.waitUntil(15_000) { saved || error != null }
            assertNull(error)
            assertTrue(saved && photo.length() > 0L)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(photo.path, bounds)
            assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
        } finally { photo.delete() }

        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForPreview()
        pinch(true)
        waitForRatio(3f)
        pinch(false)
        waitForRatio(1f)
        assertTrue(controller.ready && !controller.failed)
    }
}
