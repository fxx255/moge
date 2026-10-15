package com.moge.app.ui.photo

import android.app.Application
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class CropViewportStateTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var state: CropViewportState
    private val image = IntSize(3000, 4000)
    private fun mount() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { CropViewportState(scope) }
        }
        compose.runOnIdle { state.reset(image); state.resize(IntSize(1000, 1000)) }
    }

    @Test fun `zoom animation preserves source crop and a new gesture interrupts it`() {
        mount()
        compose.mainClock.autoAdvance = false
        lateinit var original: PixelCrop
        var initialZoom = 0f
        compose.runOnIdle {
            state.drag(CropDragMode.BOTTOM_RIGHT, Offset(-250f, -400f), 24f)
            original = pixels()
            initialZoom = state.zoom
            state.fitSelection(true)
        }
        compose.mainClock.advanceTimeBy(96)
        compose.runOnIdle {
            assertTrue(state.zoom > initialZoom)
            assertEquals(original, pixels())
            state.interrupt()
        }
        val stopped = state.framing
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(stopped, state.framing) }
        compose.mainClock.autoAdvance = true
    }

    @Test fun `disabled animations fit immediately and pinch and rotation remain available`() {
        mount()
        compose.runOnIdle {
            state.drag(CropDragMode.BOTTOM_RIGHT, Offset(-300f, -300f), 24f)
            val selected = pixels()
            state.fitSelection(false)
            assertEquals(selected, pixels())
            assertTrue(state.cropRect.width == 1000f || state.cropRect.height == 1000f)
            val zoom = state.zoom
            state.transform(1.5f, Offset(30f, -20f), state.cropRect.center)
            assertTrue(state.zoom > zoom)
            state.reset(IntSize(image.height, image.width))
            assertTrue(isWholePhotoSelected(IntSize(image.height, image.width), state.viewport, state.framing))
        }
    }

    @Test fun `deep auto fit can pinch out and return and minimum zoom shows the whole photo`() {
        mount()
        compose.runOnIdle {
            state.drag(CropDragMode.BOTTOM_RIGHT, Offset(-726f, -976f), 24f)
            state.fitSelection(false)
            val fittedZoom = state.zoom
            assertTrue(fittedZoom > 8f)
            state.transform(.5f, Offset.Zero, state.cropRect.center)
            state.transform(2f, Offset.Zero, state.cropRect.center)
            assertEquals(fittedZoom, state.zoom, .001f)
            state.transform(.0001f, Offset.Zero, state.cropRect.center)
            assertEquals(Offset.Zero, state.imageOffset)
            val full = displayedImageRect(image, state.viewport, state.zoom, state.imageOffset)
            assertTrue(full.left >= -.01f && full.top >= -.01f && full.right <= 1000.01f && full.bottom <= 1000.01f)
        }
    }

    private fun pixels() = cropBoundsInSource(image, image, state.viewport, state.zoom, state.imageOffset, state.cropRect)

    @Test fun `auto fitted crop survives repeated pinch out and edge drags on fractional bounds`() {
        mount()
        compose.runOnIdle {
            val source = IntSize(733, 1599)
            state.reset(source)
            state.resize(IntSize(1001, 1001))
            repeat(8) {
                state.drag(CropDragMode.BOTTOM_RIGHT, Offset(-400f, -900f), 24f)
                state.fitSelection(false)
                state.transform(.01f, Offset(70f, -60f), state.cropRect.center)
                for (mode in listOf(CropDragMode.LEFT_EDGE, CropDragMode.TOP_LEFT, CropDragMode.BOTTOM_RIGHT, CropDragMode.MOVE_CROP)) {
                    state.drag(mode, Offset(1000f, -1000f), 24f)
                }
                val crop = cropBoundsInSource(source, source, state.viewport, state.zoom, state.imageOffset, state.cropRect)
                assertTrue(crop.width > 0 && crop.height > 0)
                assertTrue(crop.left + crop.width <= source.width && crop.top + crop.height <= source.height)
            }
        }
    }
}
