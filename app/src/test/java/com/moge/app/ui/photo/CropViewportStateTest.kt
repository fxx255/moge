package com.moge.app.ui.photo

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
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
    private fun mount(gestures: Boolean = false) {
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember { CropViewportState(scope) }
            if (gestures) Box(Modifier.size(240.dp).onSizeChanged(state::resize)
                .testTag("crop-area").cropGestures(state, saving = false, motionEnabled = true))
        }
        compose.runOnIdle {
            state.reset(image)
            if (!gestures) state.resize(IntSize(1000, 1000))
        }
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
            state.finishGesture(true)
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
            state.finishGesture(false)
            assertEquals(selected, pixels())
            assertTrue(state.cropRect.width == 940f || state.cropRect.height == 940f)
            val zoom = state.zoom
            state.transform(1.5f, Offset(30f, -20f), state.cropRect.center)
            assertTrue(state.zoom > zoom)
            state.reset(IntSize(image.height, image.width))
            assertTrue(isWholePhotoSelected(IntSize(image.height, image.width), state.viewport, state.framing))
        }
    }

    @Test fun `deep auto fit can pinch out and return and over shrinking keeps the whole photo valid`() {
        mount()
        compose.runOnIdle {
            state.drag(CropDragMode.BOTTOM_RIGHT, Offset(-726f, -976f), 24f)
            state.finishGesture(false)
            val fittedZoom = state.zoom
            assertTrue(fittedZoom > 8f)
            state.transform(.5f, Offset.Zero, state.cropRect.center)
            state.transform(2f, Offset.Zero, state.cropRect.center)
            assertEquals(fittedZoom, state.zoom, .001f)
            state.transform(.0001f, Offset.Zero, state.cropRect.center)
            assertEquals(Offset.Zero, state.imageOffset)
            assertTrue(isWholePhotoSelected(image, state.viewport, state.framing))
            val full = displayedImageRect(image, state.viewport, state.zoom, state.imageOffset)
            assertTrue(full.left >= -.01f && full.top >= -.01f && full.right <= 1000.01f && full.bottom <= 1000.01f)
        }
    }

    @Test fun `pinching preserves selection and waits for a frame adjustment before fitting again`() {
        mount(gestures = true)
        compose.mainClock.autoAdvance = false
        try {
            lateinit var selected: PixelCrop
            var fittedZoom = 0f
            compose.runOnIdle {
                selectAndMove()
                selected = pixels()
                fittedZoom = state.zoom
            }
            val area = compose.onNodeWithTag("crop-area")
            area.performTouchInput {
                down(0, center - Offset(width * .35f, 0f))
                down(1, center + Offset(width * .35f, 0f))
                moveTo(0, center - Offset(width * .29f, 0f), delayMillis = 32)
                moveTo(1, center + Offset(width * .29f, 0f), delayMillis = 32)
            }
            compose.runOnIdle {
                assertTrue(state.zoom < fittedZoom)
                assertEquals(selected, pixels())
                assertFalse(isWholePhotoSelected(image, state.viewport, state.framing))
            }
            area.performTouchInput {
                val pan = Offset(width * .03f, height * .04f)
                moveTo(0, center - Offset(width * .29f, 0f) + pan, delayMillis = 32)
                moveTo(1, center + Offset(width * .29f, 0f) + pan, delayMillis = 32)
                up(0)
                moveTo(1, center + Offset(width * .29f, 0f) + pan * 2f, delayMillis = 32)
            }
            compose.runOnIdle { assertEquals("Pan and a remaining finger keep the same source selection", selected, pixels()) }
            val shrunk = state.framing
            area.performTouchInput { up(1) }
            compose.mainClock.advanceTimeBy(240)
            compose.runOnIdle {
                assertEquals("Releasing a manual pinch must leave the frame in place", shrunk, state.framing)
                assertEquals(selected, pixels())
            }
            area.performTouchInput { down(center); up() }
            compose.mainClock.advanceTimeBy(240)
            compose.runOnIdle { assertEquals("A tap must not fit a manually shrunk frame", shrunk, state.framing) }

            val handle = state.cropRect.bottomRight - Offset(1f, 1f)
            area.performTouchInput {
                down(handle)
                moveBy(Offset(-12f, -16f), delayMillis = 32)
            }
            val adjustedSelection = pixels()
            assertNotEquals(selected, adjustedSelection)
            area.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle {
                assertTrue("Adjusting the frame enables fitting again", state.zoom > shrunk.zoom)
                assertEquals(adjustedSelection, pixels())
            }
            compose.mainClock.advanceTimeBy(240)
            compose.runOnIdle {
                assertEquals(CROP_FIT_FRACTION, maxOf(state.cropRect.width / state.viewport.width,
                    state.cropRect.height / state.viewport.height), .0001f)
                assertEquals(adjustedSelection, pixels())
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun `whole photo scale and slight overshrink retain selection until the extra reset margin is crossed`() {
        mount()
        compose.runOnIdle {
            selectAndMove()
            val selected = pixels()
            val minimumZoom = minimumCropZoom(image, state.viewport)
            state.transform(minimumZoom * 1.02f / state.zoom, Offset(80f, -60f), Offset(50f, 900f))
            assertEquals(selected, pixels())
            assertFalse(isWholePhotoSelected(image, state.viewport, state.framing))
            state.transform(minimumZoom / state.zoom, Offset(80f, -60f), state.cropRect.center)
            assertEquals("Whole-photo fitted scale alone must not reset", selected, pixels())
            assertFalse(isWholePhotoSelected(image, state.viewport, state.framing))
            state.transform(.95f, Offset.Zero, state.cropRect.center)
            assertEquals("A slight overshrink still retains the selected content", selected, pixels())
            val shrunk = state.framing
            state.finishGesture(false)
            assertEquals("Releasing inside the margin must not enlarge the frame", shrunk, state.framing)
            assertEquals(selected, pixels())
            state.transform(minimumZoom * .89f / state.zoom, Offset(80f, -60f), state.cropRect.center)
            assertTrue(isWholePhotoSelected(image, state.viewport, state.framing))
            assertEquals(PixelCrop(0, 0, image.width, image.height), pixels())
            state.transform(.2f, Offset(100f, 300f), state.cropRect.center)
            state.finishGesture(false)
            assertEquals(minimumZoom * CROP_FIT_FRACTION, state.zoom, .0001f)
            assertEquals(Offset.Zero, state.imageOffset)
            assertTrue(isWholePhotoSelected(image, state.viewport, state.framing))
            assertEquals(PixelCrop(0, 0, image.width, image.height), pixels())
        }
    }

    @Test fun `continuous deep pinch resets to whole photo then quickly recovers with interruptible animation`() {
        mount(gestures = true)
        compose.mainClock.autoAdvance = false
        try {
            val area = compose.onNodeWithTag("crop-area")
            compose.runOnIdle { selectAndMove() }
            val fitted = minimumCropZoom(image, state.viewport) * CROP_FIT_FRACTION
            area.performTouchInput {
                down(0, center - Offset(width * .3f, 0f))
                down(1, center + Offset(width * .3f, 0f))
                moveTo(0, center - Offset(width * .06f, 0f), delayMillis = 32)
                moveTo(1, center + Offset(width * .06f, 0f), delayMillis = 32)
            }
            var earlier = 0f
            compose.runOnIdle {
                assertTrue("Pinching must continue below the whole-photo fitted scale", state.zoom < fitted / 2f)
                assertTrue(isWholePhotoSelected(image, state.viewport, state.framing))
                assertEquals(PixelCrop(0, 0, image.width, image.height), pixels())
                earlier = state.zoom
            }
            area.performTouchInput {
                moveTo(0, center - Offset(width * .03f, 0f), delayMillis = 32)
                moveTo(1, center + Offset(width * .03f, 0f), delayMillis = 32)
            }
            compose.runOnIdle { assertTrue("Further shrinking must still change the displayed photo", state.zoom < earlier) }
            val from = state.framing
            val selected = pixels()
            area.performTouchInput { up(0); up(1) }
            compose.mainClock.advanceTimeBy(48)
            compose.runOnIdle {
                assertTrue("Recovery must present intermediate frames", state.zoom > from.zoom && state.zoom < fitted)
                assertEquals(selected, pixels())
            }
            // Touching an in-flight recovery must interrupt at its current frame.
            area.performTouchInput { down(center) }
            val paused = state.framing
            compose.mainClock.advanceTimeBy(200)
            compose.runOnIdle { assertEquals(paused, state.framing) }
            area.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(240)
            compose.runOnIdle {
                assertEquals(fitted, state.zoom, .001f)
                assertEquals(CROP_FIT_FRACTION, maxOf(state.cropRect.width / state.viewport.width,
                    state.cropRect.height / state.viewport.height), .0001f)
                assertEquals(selected, pixels())
            }
        } finally { compose.mainClock.autoAdvance = true }
    }

    private fun pixels() = cropBoundsInSource(image, image, state.viewport, state.zoom, state.imageOffset, state.cropRect)

    private fun selectAndMove() {
        state.drag(CropDragMode.BOTTOM_RIGHT, Offset(-state.cropRect.width / 2f, -state.cropRect.height / 2f), 24f)
        state.finishGesture(false)
        state.drag(CropDragMode.MOVE_CROP, Offset(state.viewport.width * .08f, 0f), 24f)
    }

    @Test fun `holding an outward handle pull slowly reveals content and lifting stops expansion`() {
        mount(gestures = true)
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                state.drag(CropDragMode.BOTTOM_RIGHT, Offset(-state.cropRect.width * .6f, -state.cropRect.height * .6f), 24f)
                state.finishGesture(false)
            }
            val area = compose.onNodeWithTag("crop-area")
            val handle = Offset(state.cropRect.right, state.cropRect.center.y)
            area.performTouchInput {
                down(handle)
                moveTo(Offset(width * 1.02f, handle.y), delayMillis = 32)
            }
            val insideMargin = state.framing
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle { assertEquals("A small overshoot must not zoom even during a hold", insideMargin, state.framing) }
            area.performTouchInput { moveTo(Offset(width * 1.08f, handle.y), delayMillis = 32) }
            val before = pixels()
            val zoom = state.zoom
            val oppositeEdge = state.cropRect.left
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle {
                val now = pixels()
                assertTrue("A stationary outward hold must keep revealing content", state.zoom < zoom && now.width > before.width)
                assertEquals(before.left, now.left)
                assertEquals(before.top, now.top)
                assertEquals(before.top + before.height, now.top + now.height)
                assertEquals(oppositeEdge, state.cropRect.left, .001f)
            }
            val expandedSelection = pixels()
            area.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(240)
            compose.runOnIdle { assertEquals("Release only refits the selected region", expandedSelection, pixels()) }
            val stopped = state.framing
            compose.mainClock.advanceTimeBy(400)
            compose.runOnIdle { assertEquals(stopped, state.framing) }
        } finally { compose.mainClock.autoAdvance = true }
    }

    @Test fun `auto fitted crop survives repeated pinch out and edge drags on fractional bounds`() {
        mount()
        compose.runOnIdle {
            val source = IntSize(733, 1599)
            state.reset(source)
            state.resize(IntSize(1001, 1001))
            repeat(8) {
                state.drag(CropDragMode.BOTTOM_RIGHT, Offset(-400f, -900f), 24f)
                state.finishGesture(false)
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
