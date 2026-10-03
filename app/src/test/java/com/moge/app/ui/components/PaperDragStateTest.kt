package com.moge.app.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class PaperDragStateTest {
    private val source = Rect(10f, 120f, 210f, 240f)
    private val group = PaperDropTarget.Category("custom", "自建分组")
    private fun start(ids: Set<String> = setOf("a")): PaperDragState = PaperDragState().also {
        it.begin("a", "题目", ids, source, Offset(50f, 50f), 12f)
        it.stripBounds = Rect(0f, 0f, 300f, 100f)
        it.targetBounds(group, Rect(0f, 0f, 150f, 100f))
        it.targetBounds(PaperDropTarget.Delete, Rect(0f, 300f, 300f, 400f))
    }
    @Test fun `holding and releasing only selects without any drop`() {
        val state = start()
        assertTrue(state.held)
        assertNull(state.release())
        assertFalse(state.held)
        assertEquals(Offset(60f, 170f), state.visual!!.pointer)
    }
    @Test fun `dragging upward returns explicit ids and custom category`() {
        val state = start(setOf("a", "b"))
        state.move(Offset(0f, -120f))
        assertEquals(group, state.hovered)
        assertEquals(PaperDrop(setOf("a", "b"), group), state.release())
        assertTrue(state.settling)
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
    @Test fun `cancelling over target never mutates data`() {
        val state = start()
        state.move(Offset(0f, 170f))
        assertNull(state.release(cancelled = true))
    }
    @Test fun `offscreen horizontal group is not a valid target`() {
        val state = start()
        val offscreen = PaperDropTarget.Category("offscreen", "更多分组")
        state.targetBounds(offscreen, Rect(310f, 0f, 450f, 100f))
        state.move(Offset(300f, -120f))
        assertNull(state.hovered)
        assertNull(state.release())
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
        state.targetBounds(target, Rect(150f, 0f, 300f, 100f))
        state.move(Offset(160f, -120f))
        assertEquals(target, state.release()!!.target)
        state.finishSettling()
        assertNull(state.visual)
        assertFalse(state.settling)
    }
}
