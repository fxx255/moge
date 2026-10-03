package com.moge.app.ui.notebook

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class CategoryDragStateTest {
    private val ids = listOf("a", "b", "c", "d")

    private fun state(): CategoryDragState = CategoryDragState().also { state ->
        state.rootBounds = Rect(0f, 0f, 320f, 800f)
        state.listBounds = Rect(0f, 0f, 320f, 500f)
        state.deleteBounds = Rect(0f, 560f, 320f, 800f)
        state.sync(ids, busy = false)
        state.rowLayout = {
            state.order.mapIndexed { index, id -> id to Rect(16f, index * 100f, 304f, index * 100f + 80f) }.toMap()
        }
        ids.forEachIndexed { index, id ->
            state.registerHandle(id) { Rect(256f, index * 100f, 304f, index * 100f + 80f) }
            state.registerOrigin(id) { Rect(16f, index * 100f, 304f, index * 100f + 80f) }
        }
    }

    private fun start(state: CategoryDragState, id: String = "a") {
        val origin = state.rowLayout().getValue(id)
        assertTrue(state.begin(id, "分类 $id", Offset(280f, origin.center.y), 12f))
    }

    @Test fun `transparent delete overlay over the list cancels a previous reorder preview`() {
        val state = state()
        state.listBounds = state.rootBounds
        state.deleteBounds = Rect(0f, 240f, 320f, 800f)
        start(state)
        state.moveTo(Offset(280f, 160f))
        assertEquals(listOf("b", "a", "c", "d"), state.order)
        val corner = Offset(3f, state.deleteBounds.top + state.deleteBounds.height * 0.02f)
        state.moveTo(corner)
        assertFalse(state.deleteArmed)
        assertEquals(listOf("b", "a", "c", "d"), state.order)
        assertNull(state.release(ids, busy = false))
        assertEquals(ids, state.order)
        assertEquals(CategorySettlement.Return, state.visual!!.settlement)
    }

    @Test fun `only the visible handle can initiate the gesture`() {
        val state = state()
        assertEquals("a", state.handleAt(Offset(280f, 40f)))
        assertNull(state.handleAt(Offset(100f, 40f)))
        state.listBounds = Rect(0f, 50f, 320f, 500f)
        assertNull(state.handleAt(Offset(280f, 40f)))
        state.unregister("b")
        assertNull(state.handleAt(Offset(280f, 140f)))
    }

    @Test fun `preview crosses multiple cards but emits one relative move only on release`() {
        val state = state()
        start(state)
        state.moveTo(Offset(280f, 160f))
        assertEquals(listOf("b", "a", "c", "d"), state.order)
        state.moveTo(Offset(280f, 260f))
        assertEquals(listOf("b", "c", "a", "d"), state.order)
        assertTrue(state.held)
        assertFalse(state.pending)
        assertEquals(CategoryDrop.Reorder("a", 2), state.release(ids, busy = false))
        assertNull(state.release(ids, busy = false))
        assertTrue(state.pending)
    }

    @Test fun `returning across cards restores the original order and makes no mutation`() {
        val state = state()
        start(state)
        state.moveTo(Offset(280f, 260f))
        state.moveTo(Offset(280f, 10f))
        assertEquals(ids, state.order)
        assertNull(state.release(ids, busy = false))
        assertEquals(CategorySettlement.Return, state.visual!!.settlement)
    }

    @Test fun `release computes offset against the latest repository order`() {
        val state = state()
        start(state)
        state.moveTo(Offset(280f, 260f))
        // Another writer moved b ahead of a while the finger was held.
        val latest = listOf("b", "a", "c", "d")
        assertEquals(CategoryDrop.Reorder("a", 1), state.release(latest, busy = false))
        assertEquals(listOf("b", "c", "a", "d"), state.order)
    }

    @Test fun `added and removed neighbors reconcile by stable ID`() {
        val state = state()
        start(state)
        state.moveTo(Offset(280f, 260f))
        state.sync(listOf("x", "a", "b", "d"), busy = false)
        assertEquals(listOf("x", "b", "a", "d"), state.order)
        assertEquals(CategoryDrop.Reorder("a", 1), state.release(listOf("x", "a", "b", "d"), busy = false))
    }

    @Test fun `pending repository work prevents another move against stale order`() {
        val state = state()
        start(state)
        state.moveTo(Offset(280f, 260f))
        state.release(ids, busy = false)
        state.finishSettling()
        assertFalse(state.begin("b", "分类 b", Offset(280f, 40f), 12f))
        assertNull(state.reorderByAction("b", 1, ids, busy = false))
        state.sync(ids, busy = true)
        assertTrue(state.pending)
        state.sync(listOf("b", "c", "a", "d"), busy = true)
        state.sync(listOf("b", "c", "a", "d"), busy = false)
        assertFalse(state.locked)
        start(state, "a")
        state.moveTo(Offset(280f, 10f))
        assertEquals(CategoryDrop.Reorder("a", -2), state.release(listOf("b", "c", "a", "d"), busy = false))
    }

    @Test fun `failed async reorder rolls preview back and unlocks`() {
        val state = state()
        start(state)
        state.moveTo(Offset(280f, 260f))
        state.release(ids, busy = false)
        state.sync(ids, busy = true)
        state.sync(ids, busy = false)
        state.finishSettling()
        assertEquals(ids, state.order)
        assertFalse(state.locked)
    }

    @Test fun `unchanged order with unobserved busy transition unlocks on reconciliation without replay`() {
        val state = state()
        start(state)
        state.moveTo(Offset(280f, 260f))
        assertEquals(CategoryDrop.Reorder("a", 2), state.release(ids, busy = false))
        state.finishSettling()
        // A fast repository failure left the same IDs; Compose saw only busy=false.
        state.sync(ids, busy = false)
        assertTrue(state.pending)
        assertTrue(state.reconcilePending(ids, busy = false))
        assertEquals(ids, state.order)
        assertFalse(state.locked)
        assertFalse(state.reconcilePending(ids, busy = false))
        assertNull(state.release(ids, busy = false))
        start(state)
        assertNull(state.release(ids, busy = false))
    }

    @Test fun `pending reconciliation cannot unlock an operation that is still busy`() {
        val state = state()
        assertEquals(CategoryDrop.Reorder("a", 2), state.reorderByAction("a", 2, ids, busy = false))
        assertFalse(state.reconcilePending(ids, busy = true))
        assertTrue(state.pending)
        assertTrue(state.reconcilePending(listOf("b", "c", "a", "d"), busy = false))
        assertEquals(listOf("b", "c", "a", "d"), state.order)
    }

    @Test fun `curved delete rejects transparent corners and uses pointer rather than card overlap`() {
        val state = state()
        start(state)
        state.moveTo(Offset(2f, 564f))
        assertFalse(state.deleteArmed)
        assertNull(state.release(ids, busy = false))
        state.finishSettling()
        start(state)
        state.moveTo(Offset(160f, 564f))
        assertTrue(state.deleteArmed)
        assertEquals(CategoryDrop.Delete("a"), state.release(ids, busy = false))
        assertEquals(CategorySettlement.Delete, state.visual!!.settlement)
        assertEquals(ids, state.order)
        assertFalse(state.pending)
    }

    @Test fun `cancelling over delete or a reorder target never returns an operation`() {
        for (point in listOf(Offset(280f, 260f), Offset(160f, 700f))) {
            val state = state()
            start(state)
            state.moveTo(point)
            assertNull(state.release(ids, busy = false, cancelled = true))
            assertEquals(ids, state.order)
            assertEquals(CategorySettlement.Return, state.visual!!.settlement)
            assertEquals(state.visual!!.start, state.visual!!.pointer)
            state.finishSettling()
            assertNull(state.visual)
        }
    }

    @Test fun `moving away from deletion and releasing outside the page cancels`() {
        val state = state()
        start(state)
        state.moveTo(Offset(160f, 700f))
        assertTrue(state.deleteArmed)
        state.moveTo(Offset(350f, 700f))
        assertFalse(state.deleteArmed)
        assertNull(state.release(ids, busy = false))
    }

    @Test fun `hold without travel and sub slop travel never mutate`() {
        val state = state()
        start(state)
        state.deleteBounds = state.rowLayout().getValue("a")
        state.moveTo(state.visual!!.start + Offset(1f, 1f))
        assertFalse(state.deleteArmed)
        assertNull(state.release(ids, busy = false))
    }

    @Test fun `source disappearance and busy transition cancel safely before finger release`() {
        for (busy in listOf(false, true)) {
            val state = state()
            start(state)
            state.moveTo(Offset(160f, 700f))
            val latest = if (busy) ids else ids.drop(1)
            state.sync(latest, busy)
            assertFalse(state.held)
            assertEquals(CategorySettlement.Return, state.visual!!.settlement)
            assertNull(state.release(latest, busy))
            state.finishSettling()
            assertEquals(latest, state.order)
        }
    }

    @Test fun `release reevaluates live slots and delete bounds without finger movement`() {
        val state = state()
        start(state)
        state.moveTo(Offset(160f, 700f))
        state.deleteBounds = Rect(0f, 720f, 320f, 800f)
        assertNull(state.release(ids, busy = false))
        state.finishSettling()
        start(state)
        state.moveTo(Offset(280f, 160f))
        state.rowLayout = { mapOf("b" to Rect(16f, 180f, 304f, 260f), "c" to Rect(16f, 280f, 304f, 360f)) }
        assertNull(state.release(ids, busy = false))
    }

    @Test fun `accessible moves share pending guard and validate ends and missing IDs`() {
        val state = state()
        assertNull(state.reorderByAction("a", -1, ids, busy = false))
        assertNull(state.reorderByAction("missing", 1, ids, busy = false))
        assertNull(state.reorderByAction("b", 1, ids, busy = true))
        assertEquals(CategoryDrop.Reorder("a", 2), state.reorderByAction("a", 2, ids, busy = false))
        assertEquals(listOf("b", "c", "a", "d"), state.order)
        assertNull(state.reorderByAction("a", 1, ids, busy = false))
    }
}
