package com.moge.app.ui.capture

import android.Manifest
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
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
import kotlin.math.roundToInt

/** Uses the actual emulator preview transform and native touch delivery, without a model request. */
@RunWith(AndroidJUnit4::class)
class CameraTapMeteringDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun findPreview(view: View = compose.activity.window.decorView): PreviewView? = when (view) {
        is PreviewView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findPreview(view.getChildAt(it)) }
        else -> null
    }

    @Test fun tapFocusAppearsAtTheSelectedPointAndClearsOnPauseAndTimeout() {
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        val controller = ViewfinderController()
        compose.setContent { MogeTheme {
            CameraViewfinder(controller, Modifier.fillMaxSize().background(Color.Black))
        } }
        compose.waitUntil(30_000) {
            var streaming = false
            compose.runOnUiThread { streaming = findPreview()?.previewStreamState?.value == PreviewView.StreamState.STREAMING }
            streaming || controller.failed
        }
        assertTrue("The MuMu 12-2 camera must bind successfully", controller.ready && !controller.failed)
        val preview = compose.onNodeWithTag("camera-preview")
        compose.onNodeWithTag("camera-focus-indicator").assertDoesNotExist()
        preview.performTouchInput {
            swipe(Offset(width * .3f, height * .45f), Offset(width * .7f, height * .55f), 250)
        }
        compose.onNodeWithTag("camera-focus-indicator").assertDoesNotExist()
        var blackBar = false
        compose.runOnUiThread {
            val view = requireNotNull(findPreview())
            val point = view.meteringPointFactory.createPoint(view.width / 2f, 2f)
            blackBar = point.x !in 0f..1f || point.y !in 0f..1f
        }
        if (blackBar) {
            preview.performTouchInput { click(Offset(width / 2f, 2f)) }
            compose.onNodeWithTag("camera-focus-indicator").assertDoesNotExist()
        }
        preview.performTouchInput { click(Offset(width * .3f, height * .45f)) }
        compose.onNodeWithTag("camera-focus-indicator").assertExists()
        snapshot("camera-tap-left.png")
        val info = ProcessCameraProvider.getInstance(instrumentation.targetContext).get(5, TimeUnit.SECONDS)
            .getCameraInfo(CameraSelector.DEFAULT_BACK_CAMERA)
        val exposure = info.exposureState
        val range = exposure.exposureCompensationRange
        if (exposure.isExposureCompensationSupported && range.upper > range.lower) {
            val step = exposure.exposureCompensationStep.toFloat()
            val defaultIndex = if (step.isFinite() && step > 0f)
                (1f / 3f / step).roundToInt().coerceIn(range.lower, range.upper) else 0
            if (defaultIndex > 0) compose.waitUntil(10_000) { info.exposureState.exposureCompensationIndex == defaultIndex }
            compose.onNodeWithTag("camera-exposure-sun").assertIsEnabled().performClick()
            compose.onNodeWithTag("camera-exposure-slider").performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(.75f) }
            val expected = (range.lower + .75f * (range.upper - range.lower)).roundToInt()
            compose.waitUntil(10_000) { info.exposureState.exposureCompensationIndex == expected }
            compose.onNodeWithTag("camera-focus-indicator").assertExists()
            snapshot("camera-exposure-adjustment.png")
        } else compose.onNodeWithTag("camera-exposure-sun").assertIsNotEnabled()
        preview.performTouchInput { click(Offset(width * .7f, height * .55f)) }
        compose.onNodeWithTag("camera-exposure-slider").assertDoesNotExist()
        if (exposure.isExposureCompensationSupported && range.upper > range.lower) {
            assertEquals("A new metering point must preserve manual brightness",
                (range.lower + .75f * (range.upper - range.lower)).roundToInt(), info.exposureState.exposureCompensationIndex)
        }
        compose.onNodeWithTag("camera-focus-indicator").assertExists()
        snapshot("camera-tap-right.png")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("camera-focus-indicator").assertDoesNotExist()
        compose.waitUntil(10_000) {
            var valid = false
            compose.runOnUiThread {
                findPreview()?.let {
                    val point = it.meteringPointFactory.createPoint(it.width / 2f, it.height / 2f)
                    valid = point.x in 0f..1f && point.y in 0f..1f
                }
            }
            valid
        }
        preview.performTouchInput { click(center) }
        compose.onNodeWithTag("camera-focus-indicator").assertExists()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("camera-focus-indicator").fetchSemanticsNodes().isEmpty() }
        assertTrue(controller.ready)
    }

    private fun snapshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            try {
                File(instrumentation.targetContext.getExternalFilesDir(null), name).outputStream()
                    .use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }
}
