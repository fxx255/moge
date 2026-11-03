package com.moge.app.ui.capture

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
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
import java.io.File

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

    @Test fun `capture and pause remove the whole expanded control on the next frame`() {
        val enabled = mutableStateOf(true)
        compose.setContent { MogeTheme {
            Box(Modifier.size(300.dp)) {
                CameraMeteringOverlay(CameraMeteringTarget(1, Offset(120f, 100f)), .6f, true, enabled.value,
                    {}, {}, Modifier.matchParentSize())
            }
        } }
        compose.onNodeWithTag("camera-exposure-sun").performClick()
        compose.onNodeWithTag("camera-exposure-slider").assertExists()
        compose.runOnIdle { enabled.value = false }
        compose.onNodeWithTag("camera-exposure-sun").assertDoesNotExist()
        compose.onNodeWithTag("camera-exposure-slider").assertDoesNotExist()
        compose.onNodeWithTag("camera-focus-indicator").assertDoesNotExist()
        compose.runOnIdle { enabled.value = true }
        compose.onNodeWithTag("camera-exposure-slider").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w400dp-h300dp-xhdpi")
    fun `thin slider remains draggable with a full touch target and can render a style sample`() {
        val value = mutableFloatStateOf(.5f)
        var view: View? = null
        val source = System.getenv("CAMERA_STYLE_BACKGROUND")?.let(BitmapFactory::decodeFile)
        // The supplied screenshot's left half contains only the photographed page.
        val background = source?.let { Bitmap.createBitmap(it, 0, 0, it.width / 2, it.height) }
        try {
            compose.setContent { MogeTheme {
                val current = LocalView.current
                val position = with(LocalDensity.current) { Offset(170.dp.toPx(), 100.dp.toPx()) }
                SideEffect { view = current }
                Box(Modifier.size(360.dp, 240.dp).background(Color(0xFF706D64))) {
                    if (background != null) Image(background.asImageBitmap(), null,
                        Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    CameraMeteringOverlay(CameraMeteringTarget(1, position), value.floatValue,
                        true, true, { value.floatValue = it }, {}, Modifier.matchParentSize())
                }
            } }
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("camera-exposure-sun").performClick()
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithTag("camera-exposure-slider").performTouchInput {
                swipe(Offset(width * .35f, height / 2f), Offset(width * .75f, height / 2f), 300)
            }
            compose.mainClock.advanceTimeByFrame()
            assertTrue(value.floatValue > .65f)
            if (System.getenv("CAMERA_STYLE_SAMPLES") == "1") compose.runOnIdle {
                val root = requireNotNull(view)
                val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                try {
                    root.draw(Canvas(bitmap))
                    val file = File("build/camera-samples/exposure-thin.png").apply { parentFile!!.mkdirs() }
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally { bitmap.recycle() }
            }
            compose.onNodeWithTag("camera-exposure-slider").assertTouchHeightIsEqualTo(48.dp)
            compose.onNodeWithTag("camera-exposure-slider").performTouchInput {
                swipe(Offset(width * .25f, height * .15f), Offset(width * .85f, height * .15f), 300)
            }
            compose.mainClock.advanceTimeByFrame()
            assertTrue("The invisible touch area must also accept drags above the rail", value.floatValue > .75f)
        } finally {
            compose.mainClock.autoAdvance = true
            background?.recycle(); source?.recycle()
        }
    }
}
