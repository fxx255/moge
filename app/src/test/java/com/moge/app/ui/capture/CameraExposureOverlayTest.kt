package com.moge.app.ui.capture

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalTestApi::class)
class CameraExposureOverlayTest {
    @get:Rule val compose = createComposeRule(StandardTestDispatcher())

    @Test fun `sun opens an adjustable slider keeps its marker alive and a new focus point closes it`() {
        val target = mutableStateOf<CameraMeteringTarget?>(CameraMeteringTarget(1, Offset(100f, 100f)))
        val value = mutableFloatStateOf(.5f)
        var renderedTarget: Long? = null
        compose.setContent { MogeTheme {
            Box(Modifier.size(300.dp)) {
                SideEffect { renderedTarget = target.value?.id }
                CameraMeteringOverlay(target.value, value.floatValue, true, true, { value.floatValue = it },
                    { id -> if (target.value?.id == id) target.value = null }, Modifier.matchParentSize())
            }
        } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("camera-exposure-sun").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag("camera-exposure-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(.75f) }
        assertEquals(.75f, value.floatValue, .001f)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("camera-focus-indicator").assertExists()
        compose.onNodeWithTag("camera-exposure-slider").assertExists()
        compose.runOnUiThread {
            target.value = CameraMeteringTarget(2, Offset(200f, 200f))
            Snapshot.sendApplyNotifications()
        }
        // BoxWithConstraints measures its subcomposition on the following frame.
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        assertEquals(2L, renderedTarget)
        compose.onNodeWithTag("camera-exposure-slider").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("camera-exposure-sun").assertDoesNotExist()
        compose.mainClock.autoAdvance = true
    }
}
