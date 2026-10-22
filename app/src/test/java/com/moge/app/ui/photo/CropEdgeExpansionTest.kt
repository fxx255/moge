package com.moge.app.ui.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test

class CropEdgeExpansionTest {
    private val image = IntSize(4000, 4000)
    private val viewport = IntSize(1000, 1000)

    @Test fun `outward holds reveal more source while every untouched boundary stays fixed`() {
        val handles = mapOf(
            CropDragMode.LEFT_EDGE to setOf("left"), CropDragMode.RIGHT_EDGE to setOf("right"),
            CropDragMode.TOP_EDGE to setOf("top"), CropDragMode.BOTTOM_EDGE to setOf("bottom"),
            CropDragMode.TOP_LEFT to setOf("left", "top"), CropDragMode.TOP_RIGHT to setOf("right", "top"),
            CropDragMode.BOTTOM_LEFT to setOf("left", "bottom"), CropDragMode.BOTTOM_RIGHT to setOf("right", "bottom"),
        )
        for ((mode, moving) in handles) {
            var state = CropFraming(2f, Offset.Zero, Rect(0f, 0f, 1000f, 1000f))
            val selected = pixels(state)
            val target = outsideTarget(mode, state.cropRect, .08f)
            repeat(60) {
                val previous = state
                state = expandCropAtEdge(state, image, viewport, mode, target, 1f / 60f, 50f, 24f)
                assertTrue("$mode should zoom out continuously", state.zoom < previous.zoom)
                val now = pixels(state)
                if ("left" !in moving) assertEquals(selected.left, now.left)
                if ("right" !in moving) assertEquals(selected.left + selected.width, now.left + now.width)
                if ("top" !in moving) assertEquals(selected.top, now.top)
                if ("bottom" !in moving) assertEquals(selected.top + selected.height, now.top + now.height)
                // The opposing screen edge/corner also stays anchored during the gesture.
                if ("left" in moving) assertEquals(1000f, state.cropRect.right, .001f)
                if ("right" in moving) assertEquals(0f, state.cropRect.left, .001f)
                if ("top" in moving) assertEquals(1000f, state.cropRect.bottom, .001f)
                if ("bottom" in moving) assertEquals(0f, state.cropRect.top, .001f)
            }
            assertTrue(pixels(state).width > selected.width || pixels(state).height > selected.height)
        }
    }

    @Test fun `all handles wait for three percent overshoot and stop again inside the margin`() {
        val wideViewport = IntSize(1000, 600)
        val current = CropFraming(2f, Offset.Zero, Rect(0f, 0f, 1000f, 600f))
        for (mode in CropDragMode.entries.filter { it.isResize }) {
            for (fraction in listOf(-.02f, 0f, .029f, .03f)) {
                val target = outsideTarget(mode, current.cropRect, fraction)
                assertEquals("$mode must allow ordinary resizing and the full margin", current,
                    expandCropAtEdge(current, image, wideViewport, mode, target, .03f, 50f, 24f))
            }
            val justOutside = expandCropAtEdge(current, image, wideViewport, mode,
                outsideTarget(mode, current.cropRect, .031f), .03f, 50f, 24f)
            val furtherOutside = expandCropAtEdge(current, image, wideViewport, mode,
                outsideTarget(mode, current.cropRect, .06f), .03f, 50f, 24f)
            assertTrue("$mode starts gently after the threshold", justOutside.zoom < current.zoom)
            assertTrue("$mode speeds up smoothly as the pull increases", furtherOutside.zoom < justOutside.zoom)
            assertEquals("$mode stops when brought back into the margin", justOutside,
                expandCropAtEdge(justOutside, image, wideViewport, mode,
                    outsideTarget(mode, current.cropRect, .02f), .03f, 50f, 24f))
        }
    }

    @Test fun `holding at the source boundary or away from the viewport edge does not shrink`() {
        val full = initialCropFraming(image, viewport)
        assertEquals(full, expandCropAtEdge(full, image, viewport, CropDragMode.LEFT_EDGE,
            Offset(-40f, 500f), .03f, 50f, 24f))
        val middle = CropFraming(2f, Offset.Zero, Rect(200f, 200f, 800f, 800f))
        assertEquals(middle, expandCropAtEdge(middle, image, viewport, CropDragMode.RIGHT_EDGE,
            Offset(800f, 500f), .03f, 50f, 24f))
        assertEquals(middle, expandCropAtEdge(middle, image, viewport, CropDragMode.MOVE_CROP,
            Offset(1000f, 500f), .03f, 50f, 24f))
    }

    private fun pixels(state: CropFraming) = cropBoundsInSource(image, image, viewport,
        state.zoom, state.imageOffset, state.cropRect)

    private fun outsideTarget(mode: CropDragMode, viewportBounds: Rect, fraction: Float): Offset {
        val handle = mode.handlePosition(viewportBounds)
        return handle + Offset(
            when (handle.x) { viewportBounds.left -> -viewportBounds.width; viewportBounds.right -> viewportBounds.width; else -> 0f },
            when (handle.y) { viewportBounds.top -> -viewportBounds.height; viewportBounds.bottom -> viewportBounds.height; else -> 0f },
        ) * fraction
    }
}
