package com.moge.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class PaperDragStateTest {
    @Test fun `filter row unfolds groups before reaching the upper prompt`() {
        val state = start()
        state.categoryActivationBounds = Rect(0f, 80f, 300f, 110f)
        moveTo(state, Offset(60f, 95f))
        assertTrue(state.categoriesExpanded)
    }
    @Test fun `curved delete target does not accept its transparent upper corners`() {
        val state = start()
        state.curvedDeleteZone = true
        moveTo(state, Offset(1f, 305f))
        assertNull(state.hovered)
        moveTo(state, Offset(150f, 305f))
        assertEquals(PaperDropTarget.Delete, state.hovered)
    }
    private val source = Rect(10f, 120f, 210f, 240f)
    private val group = PaperDropTarget.Category("custom", "自建分组")
    private fun start(ids: Set<String> = setOf("a")): PaperDragState = PaperDragState().also {
        it.rootBounds = Rect(0f, 0f, 300f, 500f)
        it.begin("a", "题目", ids, source, Offset(50f, 50f), 12f)
        it.categoryPromptBounds = Rect(0f, 0f, 300f, 32f)
        it.categoryBounds = Rect(0f, 32f, 300f, 100f)
        it.targetBounds(group, Rect(0f, 32f, 150f, 100f))
        it.targetBounds(PaperDropTarget.Delete, Rect(0f, 300f, 300f, 400f))
    }
    private fun expand(state: PaperDragState) {
        moveTo(state, Offset(state.visual!!.pointer.x, 16f))
        assertTrue(state.categoriesExpanded)
    }
    private fun moveTo(state: PaperDragState, point: Offset) {
        state.move(point - state.visual!!.pointer)
    }

    @Test fun `holding and releasing only selects without any drop`() {
        val state = start()
        assertTrue(state.held)
        assertFalse(state.categoriesExpanded)
        assertNull(state.release())
        assertFalse(state.held)
        assertEquals(Offset(60f, 170f), state.visual!!.pointer)
    }
    @Test fun `moving upward below the prompt does not unfold or arm hidden categories`() {
        val state = start()
        state.move(Offset(0f, -120f))
        assertFalse(state.categoriesExpanded)
        assertNull(state.hovered)
        assertNull(state.release())
    }
    @Test fun `entering prompt unfolds categories and stays expanded after leaving`() {
        val state = start()
        expand(state)
        moveTo(state, Offset(60f, 250f))
        assertTrue(state.categoriesExpanded)
        assertNull(state.hovered)
        assertNull(state.release())
        assertFalse(state.categoriesExpanded)
    }
    @Test fun `upward movement crossing the entire prompt unfolds without waiting`() {
        val state = start()
        state.move(Offset(0f, -200f))
        assertTrue(state.categoriesExpanded)
        assertNull(state.hovered)
    }
    @Test fun `horizontal or downward movement cannot unfold categories`() {
        val state = start()
        state.move(Offset(100f, 0f))
        assertFalse(state.categoriesExpanded)
        state.move(Offset(0f, 50f))
        assertFalse(state.categoriesExpanded)
    }
    @Test fun `prompt registered after upward movement can unfold under pointer`() {
        val state = start()
        state.categoryPromptBounds = Rect.Zero
        moveTo(state, Offset(60f, 16f))
        assertFalse(state.categoriesExpanded)
        state.categoryPromptBounds = Rect(0f, 0f, 300f, 32f)
        assertTrue(state.categoriesExpanded)
    }
    @Test fun `dragging upward returns explicit ids and custom category`() {
        val state = start(setOf("a", "b"))
        expand(state)
        moveTo(state, Offset(60f, 50f))
        assertEquals(group, state.hovered)
        assertEquals(PaperDrop(setOf("a", "b"), group), state.release())
        assertTrue(state.settling)
        assertFalse(state.categoriesExpanded)
    }
    @Test fun `dragging downward arms delete only inside the visible range`() {
        val state = start()
        state.move(Offset(0f, 100f))
        assertNull(state.hovered)
        state.move(Offset(0f, 70f))
        assertEquals(PaperDropTarget.Delete, state.hovered)
        assertEquals(PaperDrop(setOf("a"), PaperDropTarget.Delete), state.release())
    }
    @Test fun `moving away from deletion cancels the drop`() {
        val state = start()
        state.move(Offset(0f, 170f))
        state.move(Offset(0f, -100f))
        assertNull(state.hovered)
        assertNull(state.release())
    }
    @Test fun `cancelling over target never mutates data and returns to source`() {
        val state = start()
        expand(state)
        moveTo(state, Offset(60f, 50f))
        assertEquals(group, state.hovered)
        assertNull(state.release(cancelled = true))
        assertEquals(state.visual!!.start, state.visual!!.pointer)
        assertFalse(state.categoriesExpanded)
    }
    @Test fun `offscreen vertical group is not a valid target until scrolled into view`() {
        val state = start()
        val offscreen = PaperDropTarget.Category("offscreen", "更多分组")
        state.targetBounds(offscreen, Rect(0f, 110f, 150f, 200f))
        expand(state)
        moveTo(state, Offset(60f, 140f))
        assertNull(state.hovered)
        state.targetBounds(offscreen, Rect(0f, 40f, 150f, 90f))
        moveTo(state, Offset(60f, 65f))
        state.removeTarget(group)
        assertEquals(offscreen, state.release()!!.target)
    }
    @Test fun `unrevealed rows cannot accept drops during downward expansion`() {
        val state = start()
        expand(state)
        state.categoryBounds = Rect(0f, 32f, 300f, 40f)
        moveTo(state, Offset(60f, 50f))
        assertNull(state.hovered)
        state.categoryBounds = Rect(0f, 32f, 300f, 100f)
        assertEquals(group, state.hovered)
        state.categoryBounds = Rect(0f, 32f, 300f, 40f)
        assertNull(state.release())
    }
    @Test fun `uncomposed or empty targets cannot accept a drop`() {
        val state = start()
        expand(state)
        moveTo(state, Offset(60f, 50f))
        assertEquals(group, state.hovered)
        state.removeTarget(group)
        assertNull(state.hovered)
        state.targetBounds(group, Rect.Zero)
        assertNull(state.release())
    }
    @Test fun `targets outside the overlay root cannot accept drops`() {
        val state = start()
        state.targetBounds(PaperDropTarget.Delete, Rect(0f, 500f, 300f, 600f))
        moveTo(state, Offset(60f, 540f))
        assertNull(state.release())
    }
    @Test fun `clipping target after hover invalidates drop without another movement`() {
        val state = start()
        state.move(Offset(0f, 170f))
        assertEquals(PaperDropTarget.Delete, state.hovered)
        state.rootBounds = Rect(0f, 0f, 300f, 320f)
        assertNull(state.hovered)
        assertNull(state.release())
    }
    @Test fun `drop animation aims at the visible portion of a clipped category`() {
        val state = start()
        expand(state)
        state.categoryBounds = Rect(0f, 32f, 300f, 60f)
        moveTo(state, Offset(60f, 50f))
        assertEquals(group, state.release()!!.target)
        assertEquals(Offset(75f, 46f), state.visual!!.pointer)
    }
    @Test fun `tiny movement cannot trigger a target revealed under the pointer`() {
        val state = start()
        state.targetBounds(PaperDropTarget.Delete, source)
        state.move(Offset(1f, 1f))
        assertNull(state.release())
    }
    @Test fun `uncategorized is an explicit drop target and settling clears old state`() {
        val state = start()
        val target = PaperDropTarget.Category(null, "未分类")
        state.targetBounds(target, Rect(150f, 32f, 300f, 100f))
        expand(state)
        moveTo(state, Offset(220f, 50f))
        assertEquals(target, state.release()!!.target)
        state.finishSettling()
        assertNull(state.visual)
        assertFalse(state.settling)
    }
    @Test fun `preview and exact origin dimensions survive movement and settling`() {
        val state = PaperDragState()
        val origin = Rect(15f, 30f, 495f, 390f)
        val preview: @Composable () -> Unit = {}
        state.begin("a", "题目", setOf("a", "b"), origin, Offset(20f, 30f), 12f, preview)
        assertSame(preview, state.visual!!.preview)
        state.move(Offset(50f, 30f))
        assertSame(preview, state.visual!!.preview)
        assertEquals(origin, state.visual!!.origin)
        state.release()
        assertSame(preview, state.visual!!.preview)
        assertEquals(origin, state.visual!!.origin)
        state.finishSettling()
        assertNull(state.visual)
    }
    @Test fun `preview is optional in begin and existing visual construction`() {
        val state = start()
        assertNull(state.visual!!.preview)
        val visual = PaperDragVisual("a", "题目", setOf("a"), source, Offset.Zero, Offset.Zero, Offset.Zero)
        assertNull(visual.preview)
    }
    @Test fun `drag captures selection and rejects another begin while held`() {
        val ids = mutableSetOf("a", "b")
        val state = start(ids)
        ids.clear()
        state.begin("other", "其他", setOf("other"), source, Offset.Zero, 12f)
        assertEquals("a", state.visual!!.id)
        assertEquals(setOf("a", "b"), state.visual!!.ids)
    }
    @Test fun `new drag resets category latch and targets from prior release`() {
        val state = start()
        expand(state)
        state.release()
        state.begin("b", "其他", setOf("b"), source, Offset(50f, 50f), 12f)
        assertFalse(state.categoriesExpanded)
        state.finishSettling()
        assertNotNull(state.visual)
        assertTrue(state.held)
        state.move(Offset(0f, 170f))
        assertNull(state.release())
    }
    @Test fun `empty source cannot start a drag`() {
        val state = PaperDragState()
        state.begin("a", "题目", setOf("a"), Rect.Zero, Offset.Zero, 12f)
        assertFalse(state.held)
        assertNull(state.visual)
    }
}
