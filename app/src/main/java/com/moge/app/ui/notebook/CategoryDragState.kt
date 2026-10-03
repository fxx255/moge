package com.moge.app.ui.notebook

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.moge.app.ui.components.curvedDeleteContains

internal const val CATEGORY_PENDING_RECONCILE_MS = 1_000L

internal sealed interface CategoryDrop {
    data class Reorder(val id: String, val offset: Int) : CategoryDrop
    data class Delete(val id: String) : CategoryDrop
}

internal enum class CategorySettlement { Return, Reorder, Delete }

internal data class CategoryDragVisual(
    val id: String,
    val name: String,
    val origin: Rect,
    val grab: Offset,
    val start: Offset,
    val pointer: Offset,
    val settlement: CategorySettlement = CategorySettlement.Return,
)

/** Preview by ID; the repository receives exactly one move, resolved against its order at release. */
@Stable
internal class CategoryDragState {
    var order by mutableStateOf<List<String>>(emptyList()); private set
    var visual by mutableStateOf<CategoryDragVisual?>(null); private set
    var held by mutableStateOf(false); private set
    var settling by mutableStateOf(false); private set
    var deleteArmed by mutableStateOf(false); private set
    var pending by mutableStateOf(false); private set
    val locked: Boolean get() = held || settling || pending

    var rootBounds = Rect.Zero
    var listBounds = Rect.Zero
    var deleteBounds = Rect.Zero
    // Lazy-list layout slots, rather than animated card positions, prevent oscillating swaps.
    var rowLayout: () -> Map<String, Rect> = { emptyMap() }
    private val handles = mutableMapOf<String, () -> Rect>()
    private val origins = mutableMapOf<String, () -> Rect>()
    private var repositoryOrder = emptyList<String>()
    private var pendingSawBusy = false
    private var moved = false
    private var touchSlop = 0f

    fun sync(ids: List<String>, busy: Boolean) {
        val latest = ids.distinct()
        val changed = latest != repositoryOrder
        repositoryOrder = latest
        if (pending) {
            pendingSawBusy = pendingSawBusy || busy
            // A new repository order is authoritative. A failed mutation restores the old order.
            if (changed || pendingSawBusy && !busy) {
                pending = false
                pendingSawBusy = false
                order = latest
            }
        }
        if (held) {
            val id = visual?.id
            if (busy || id !in latest) cancel()
            else if (changed) order = placeAtPreviewAnchor(latest, id!!)
        } else if (!pending) order = latest
    }

    private fun placeAtPreviewAnchor(latest: List<String>, id: String): List<String> {
        val oldIndex = order.indexOf(id)
        val after = order.drop(oldIndex + 1).firstOrNull { it in latest && it != id }
        val before = order.take(oldIndex.coerceAtLeast(0)).lastOrNull { it in latest && it != id }
        val remaining = latest.filterNot { it == id }.toMutableList()
        val insertion = when {
            after != null -> remaining.indexOf(after)
            before != null -> remaining.indexOf(before) + 1
            else -> latest.indexOf(id)
        }
        remaining.add(insertion.coerceIn(0, remaining.size), id)
        return remaining
    }

    fun registerHandle(id: String, bounds: () -> Rect) { handles[id] = bounds }
    fun registerOrigin(id: String, bounds: () -> Rect) { origins[id] = bounds }
    fun unregister(id: String) { handles.remove(id); origins.remove(id) }

    fun handleAt(point: Offset): String? = if (locked) null else repositoryOrder.firstOrNull { id ->
        handles[id]?.invoke()?.intersect(listBounds)?.intersect(rootBounds)?.let {
            !it.isEmpty && it.contains(point)
        } == true
    }

    fun begin(id: String, name: String, point: Offset, slop: Float): Boolean {
        val origin = origins[id]?.invoke() ?: rowLayout()[id] ?: Rect.Zero
        if (locked || id !in repositoryOrder || origin.isEmpty) return false
        order = repositoryOrder
        visual = CategoryDragVisual(id, name, origin, point - origin.topLeft, point, point)
        touchSlop = slop
        moved = false
        deleteArmed = false
        held = true
        return true
    }

    fun moveTo(point: Offset) {
        if (!held) return
        val current = visual ?: return
        visual = current.copy(pointer = point)
        if ((point - current.start).getDistance() >= touchSlop && point != current.start) moved = true
        refreshTargets()
    }

    fun refreshTargets() {
        val current = visual ?: return
        if (!held) return
        deleteArmed = moved && rootBounds.contains(current.pointer) &&
            curvedDeleteContains(deleteBounds, current.pointer)
        if (!moved || deleteArmed || !listBounds.intersect(rootBounds).contains(current.pointer)) return
        val remaining = order.filterNot { it == current.id }
        val rows = rowLayout()
        var insertion = order.indexOf(current.id)
        for ((index, id) in remaining.withIndex()) {
            val bounds = rows[id] ?: continue
            if (bounds.intersect(listBounds).intersect(rootBounds).isEmpty) continue
            insertion = if (current.pointer.y < bounds.center.y) index else index + 1
            if (current.pointer.y < bounds.center.y) break
        }
        order = remaining.toMutableList().apply {
            add(insertion.coerceIn(0, size), current.id)
        }
    }

    fun release(latestIds: List<String>, busy: Boolean, cancelled: Boolean = false): CategoryDrop? {
        if (!held) return null
        sync(latestIds, busy)
        if (!held) return null
        refreshTargets()
        val current = visual ?: return null
        val from = repositoryOrder.indexOf(current.id)
        val to = order.indexOf(current.id)
        val drop = when {
            cancelled || busy || !moved || from < 0 -> null
            deleteArmed -> CategoryDrop.Delete(current.id)
            listBounds.intersect(rootBounds).contains(current.pointer) && to >= 0 && to != from ->
                CategoryDrop.Reorder(current.id, to - from)
            else -> null
        }
        held = false
        settling = true
        deleteArmed = false
        val settlement = when (drop) {
            is CategoryDrop.Delete -> CategorySettlement.Delete
            is CategoryDrop.Reorder -> CategorySettlement.Reorder
            null -> CategorySettlement.Return
        }
        if (drop is CategoryDrop.Reorder) {
            pending = true
            pendingSawBusy = false
        } else order = repositoryOrder
        visual = current.copy(settlement = settlement)
        updateSettlementTarget()
        return drop
    }

    fun cancel() {
        if (!held) return
        val current = visual ?: return
        order = repositoryOrder
        held = false
        settling = true
        deleteArmed = false
        visual = current.copy(settlement = CategorySettlement.Return)
        updateSettlementTarget()
    }

    fun reorderByAction(id: String, offset: Int, latestIds: List<String>, busy: Boolean): CategoryDrop.Reorder? {
        sync(latestIds, busy)
        if (busy || locked || offset == 0) return null
        val from = repositoryOrder.indexOf(id)
        val to = from + offset
        if (from !in repositoryOrder.indices || to !in repositoryOrder.indices) return null
        order = repositoryOrder.toMutableList().apply { add(to, removeAt(from)) }
        pending = true
        pendingSawBusy = false
        return CategoryDrop.Reorder(id, offset)
    }

    /** Busy may be conflated away on a fast failure; reconcile without retrying any mutation. */
    fun reconcilePending(latestIds: List<String>, busy: Boolean): Boolean {
        if (!pending || busy) return false
        repositoryOrder = latestIds.distinct()
        order = repositoryOrder
        pending = false
        pendingSawBusy = false
        updateSettlementTarget()
        return true
    }

    fun updateSettlementTarget() {
        if (!settling) return
        val current = visual ?: return
        val target = if (current.settlement == CategorySettlement.Delete && !deleteBounds.isEmpty) {
            val trash = Offset(deleteBounds.center.x, deleteBounds.top + deleteBounds.height * 0.15f)
            trash + current.grab - Offset(current.origin.width / 2f, current.origin.height / 2f)
        } else {
            (rowLayout()[current.id]?.topLeft ?: current.origin.topLeft) + current.grab
        }
        visual = current.copy(pointer = target)
    }

    fun finishSettling() {
        if (!held) {
            visual = null
            settling = false
            if (!pending) order = repositoryOrder
        }
    }
}
