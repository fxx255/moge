package com.moge.app.ui.capture

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.LocalPaperMotionEnabled
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@OptIn(ExperimentalTestApi::class)
class CameraFocusIndicatorTest {
    @get:Rule val compose = createComposeRule(StandardTestDispatcher())

    @Test fun `repeated taps restart the marker lifetime without an older timeout hiding the new tap`() {
        val target = mutableStateOf<CameraMeteringTarget?>(null)
        val finished = mutableListOf<Long>()
        compose.setContent { MogeTheme {
            CompositionLocalProvider(LocalPaperMotionEnabled provides true) {
                Box(Modifier.size(300.dp)) {
                    CameraFocusIndicator(target.value, { id ->
                        finished += id
                        if (target.value?.id == id) target.value = null
                    }, Modifier.matchParentSize())
                }
            }
        } }
        compose.onNodeWithTag("camera-focus-indicator").assertDoesNotExist()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { target.value = CameraMeteringTarget(1, Offset(70f, 100f)) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnUiThread { target.value = CameraMeteringTarget(2, Offset(200f, 170f)) }
        compose.mainClock.advanceTimeBy(600)
        assertEquals("Latest tap must survive the first tap's lifetime; finished=$finished", 2L, target.value?.id)
        compose.onNodeWithTag("camera-focus-indicator").assertExists()
        assertEquals(emptyList<Long>(), finished)
        compose.mainClock.advanceTimeBy(1_500)
        compose.onNodeWithTag("camera-focus-indicator").assertDoesNotExist()
        assertEquals(listOf(2L), finished)
        compose.mainClock.autoAdvance = true
    }
}
