package com.moge.app.ui.markdown

import com.moge.app.R

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.ColorDrawable
import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.PopupWindow
import android.widget.TextView
import java.text.BreakIterator
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Platform selection handles live in separate PopupWindows: their drag events
 * never reach TextView.onTouchEvent or Compose's scrollable list. Own the handles
 * for conversation text using public APIs, retaining native text highlights and
 * the floating copy menu. No private Editor fields or Android-version reflection.
 */
internal class MarkdownSelectionController(
    private val view: TextView,
    private val host: SelectionScrollHost,
    private val handleColor: Int,
    selectionSurfaceColor: Int,
) : ViewTreeObserver.OnPreDrawListener {
    private var actionMode: ActionMode? = null
    private var active = false
    val selecting: Boolean get() = active
    var dragging = false
        private set
    var pointerY = 0f
        private set
    private var pointerX = 0f
    private var dragStart = false
    private var fixedOffset = 0
    private var fingerOffsetX = 0f
    private var fingerOffsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var selectionStartedDuringTouch = false
    private var contentSelectionGesture = false
    private var contentGestureMoved = false
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val location = IntArray(2)
    private val selectionPath = Path()
    private val selectionBounds = RectF()
    private val selectionClip = Region()
    private val paintedSelection = Region()
    private val handles by lazy { listOf(Handle(true), Handle(false)) }
    internal val magnifier = SelectionMagnifier(view, handleColor, selectionSurfaceColor)

    fun touch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            lastTouchX = event.x
            lastTouchY = event.y
            selectionStartedDuringTouch = false
            contentSelectionGesture = active
            contentGestureMoved = false
        }
        if (contentSelectionGesture) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (kotlin.math.hypot(event.x - lastTouchX, event.y - lastTouchY) > touchSlop) {
                    contentGestureMoved = true
                    view.cancelLongPress()
                }
                MotionEvent.ACTION_POINTER_DOWN -> contentGestureMoved = true
                MotionEvent.ACTION_UP -> {
                    contentSelectionGesture = false
                    if (!contentGestureMoved) finish()
                }
                MotionEvent.ACTION_CANCEL -> contentSelectionGesture = false
            }
            // Keep Editor from collapsing the range. Do not disallow parent
            // interception: the enclosing Compose list still owns browsing swipes.
            return true
        }
        if (selectionStartedDuringTouch &&
            event.actionMasked in listOf(MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
            // Do not let Editor collapse our long-press selection on finger up.
            if (event.actionMasked != MotionEvent.ACTION_MOVE) {
                selectionStartedDuringTouch = false
                view.isPressed = false
                view.cancelLongPress()
            }
            return true
        }
        return false
    }

    fun longClick(): Boolean {
        if (view.layout == null || view.text.isEmpty() || !view.isAttachedToWindow) return false
        val offset = view.getOffsetForPosition(lastTouchX, lastTouchY).coerceAtMost(view.text.length - 1)
        val formula = formulaPartForPosition(view, lastTouchX, lastTouchY)
        val word = formula?.let { MarkdownSelection(it.start, it.end) }
            ?: selectionWord(view.text.toString(), offset)
        begin(word.start, word.end)
        if (!active) return false
        selectionStartedDuringTouch = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    internal fun begin(start: Int, end: Int) {
        val text = view.text as? Spannable ?: return
        val range = snapFormulaSelection(text, start, end)
        // Finish the old menu before establishing the replacement range. Its
        // destruction callback must never clear a newly created selection or
        // leave a second pre-draw listener registered on the same TextView.
        finish()
        if (!hasVisibleRange(range.start, range.end)) return
        host.claim(this)
        active = true
        view.requestFocus()
        Selection.setSelection(text, range.start, range.end)
        view.viewTreeObserver.addOnPreDrawListener(this)
        actionMode = view.startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, android.R.id.copy, 0, android.R.string.copy)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                menu.add(0, android.R.id.selectAll, 1, android.R.string.selectAll)
                    .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                return true
            }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    android.R.id.copy -> {
                        // TextView's override restores full LaTeX instead of drawable placeholders.
                        view.onTextContextMenuItem(android.R.id.copy)
                        finish()
                    }
                    android.R.id.selectAll -> {
                        Selection.setSelection(view.text as Spannable, 0, view.text.length)
                        updateHandles()
                        mode.invalidateContentRect()
                    }
                    else -> return false
                }
                return true
            }
            override fun onDestroyActionMode(mode: ActionMode) { clear() }
            override fun onGetContentRect(mode: ActionMode, target: View, rect: Rect) {
                view.getLocationOnScreen(location)
                val a = endpoint(true)
                val b = endpoint(false)
                val bounds = host.viewport
                val y = minOf(a.second, b.second).coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom))
                rect.set(view.paddingLeft, (y - location[1] - view.lineHeight).roundToInt(),
                    view.width - view.paddingRight, (y - location[1]).roundToInt())
            }
        }, ActionMode.TYPE_FLOATING)
        if (actionMode == null) clear() else updateHandles()
    }

    fun finish() {
        actionMode?.finish()
        clear()
    }

    private fun clear() {
        if (!active) return
        active = false
        dragging = false
        contentSelectionGesture = false
        actionMode = null
        host.release(this)
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
        handles.forEach { it.popup.dismiss() }
        magnifier.dismiss()
        (view.text as? Spannable)?.let { Selection.removeSelection(it) }
    }

    override fun onPreDraw(): Boolean {
        if (!view.isAttachedToWindow || !view.hasSelection() || !view.isShown ||
            (!dragging && !hasVisibleRange(view.selectionStart, view.selectionEnd))) finish()
        else {
            updateHandles()
            if (dragging) updateMagnifier()
            if (dragging) actionMode?.hide(1_000) else actionMode?.invalidateContentRect()
        }
        return true
    }

    private fun hasVisibleRange(start: Int, end: Int): Boolean {
        if (start < 0 || end < 0 || start == end) return false
        markdownSelectionPath(view, start, end, selectionPath)
        selectionPath.computeBounds(selectionBounds, true)
        if (selectionBounds.isEmpty) return false
        selectionClip.set(floor(selectionBounds.left).toInt(), floor(selectionBounds.top).toInt(),
            ceil(selectionBounds.right).toInt(), ceil(selectionBounds.bottom).toInt())
        // Layout can return zero-width contours at both paragraph edges for a
        // terminating newline. Their combined bounds have area, but no pixels
        // are highlighted. Test the filled region rather than just its bounds.
        return paintedSelection.setPath(selectionPath, selectionClip)
    }

    internal fun containsSelection(screenX: Float, screenY: Float): Boolean {
        if (!active || !hasVisibleRange(view.selectionStart, view.selectionEnd)) return false
        view.getLocationOnScreen(location)
        return paintedSelection.contains((screenX - location[0]).toInt(), (screenY - location[1]).toInt())
    }

    private fun endpoint(start: Boolean): Pair<Float, Float> {
        val offset = (if (start) minOf(view.selectionStart, view.selectionEnd)
            else maxOf(view.selectionStart, view.selectionEnd)).coerceIn(0, view.text.length)
        val caret = selectionEndpoint(view, offset, start) ?: return 0f to 0f
        view.getLocationOnScreen(location)
        return (location[0] + caret.x) to (location[1] + caret.bottom)
    }

    private fun updateHandles() {
        if (!active || view.layout == null) return
        handles.forEach { it.place(endpoint(it.start)) }
    }

    internal fun beginDrag(start: Boolean, x: Float, y: Float) {
        if (!active) return
        dragStart = start
        fixedOffset = if (start) maxOf(view.selectionStart, view.selectionEnd)
            else minOf(view.selectionStart, view.selectionEnd)
        val position = endpoint(start)
        fingerOffsetX = x - position.first
        val offset = if (start) minOf(view.selectionStart, view.selectionEnd) else maxOf(view.selectionStart, view.selectionEnd)
        val caret = selectionEndpoint(view, offset, start)
        fingerOffsetY = y - position.second + (caret?.let { it.bottom - it.centerY } ?: (view.lineHeight / 2f))
        pointerX = x
        pointerY = y
        dragging = true
        actionMode?.hide(1_000)
        updateMagnifier()
        host.drag(this)
    }

    internal fun moveDrag(x: Float, y: Float) {
        if (!dragging) return
        pointerX = x
        pointerY = y
        extendAtPointer()
    }

    internal fun endDrag() {
        dragging = false
        magnifier.dismiss()
        host.stopScrolling()
        // Keep a temporary empty range alive during crossing, then release its
        // popups and menu if the finger stops on an invisible separator.
        if (!hasVisibleRange(view.selectionStart, view.selectionEnd)) {
            finish()
            return
        }
        actionMode?.hide(0)
        actionMode?.invalidateContentRect()
        updateHandles()
    }

    private fun updateMagnifier() {
        if (!dragging || !active) return
        val offset = if (dragStart) minOf(view.selectionStart, view.selectionEnd)
            else maxOf(view.selectionStart, view.selectionEnd)
        magnifier.show(pointerX, pointerY, host.viewport, offset, dragStart)
    }

    internal fun canExtend(speed: Float): Boolean {
        val offset = if (dragStart) minOf(view.selectionStart, view.selectionEnd)
            else maxOf(view.selectionStart, view.selectionEnd)
        return if (speed < 0) offset > 0 else offset < view.text.length
    }

    internal fun extendAtPointer() {
        if (!dragging || !active || view.layout == null) return
        if (!view.hasSelection() || fixedOffset !in 0..view.text.length) {
            finish()
            return
        }
        view.getLocationOnScreen(location)
        val bounds = host.viewport
        val y = (pointerY - fingerOffsetY).coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - 1))
        val text = view.text as? Spannable ?: return
        val offset = view.getOffsetForPosition(pointerX - fingerOffsetX - location[0], y - location[1])
            .coerceIn(0, text.length)
        if (offset != fixedOffset && dragStart != (offset < fixedOffset)) {
            dragStart = offset < fixedOffset
            // Keep the same PopupWindow receiving the gesture under the finger.
            // Only its endpoint role, shape and accessible label change.
            handles.forEach { it.start = !it.start }
        }
        // At the exact anchor retain a nonempty range on the current side;
        // crossing to the other side reverses roles without lifting the finger.
        if (dragStart) Selection.setSelection(text, offset.coerceIn(0, (fixedOffset - 1).coerceAtLeast(0)), fixedOffset)
        else Selection.setSelection(text, fixedOffset, offset.coerceIn((fixedOffset + 1).coerceAtMost(text.length), text.length))
        updateHandles()
        updateMagnifier()
    }

    private inner class Handle(initialStart: Boolean) : View(view.context) {
        var start = initialStart
            set(value) {
                field = value
                updateRoleAppearance()
            }
        private val size = (48f * host.density).roundToInt()
        private val startDrawable = SelectionHandleDrawable(handleColor, true, host.density)
        private val endDrawable = SelectionHandleDrawable(handleColor, false, host.density)
        private val drawable get() = if (start) startDrawable else endDrawable
        // The touch target extends outward, so short selected words do not stack both targets.
        private val anchorX get() = if (start) size - 6f * host.density else 6f * host.density
        val popup = PopupWindow(this, size, size, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isClippingEnabled = false
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        init {
            updateRoleAppearance()
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        private fun updateRoleAppearance() {
            contentDescription = if (start) view.context.getString(R.string.ui_selection_start_4fc2b) else view.context.getString(R.string.ui_selection_end_08e19)
            invalidate()
        }
        override fun onDraw(canvas: Canvas) {
            val left = if (start) anchorX - drawable.intrinsicWidth else anchorX
            drawable.setBounds(left.roundToInt(), 0, left.roundToInt() + drawable.intrinsicWidth, drawable.intrinsicHeight)
            drawable.draw(canvas)
        }
        fun place(position: Pair<Float, Float>) {
            val bounds = host.viewport
            val isDragged = dragging && start == dragStart
            val y = if (isDragged) position.second.coerceIn(bounds.top, maxOf(bounds.top, bounds.bottom - size))
                else position.second
            visibility = if (isDragged || (y >= bounds.top && y <= bounds.bottom - 1)) VISIBLE else INVISIBLE
            if (visibility != VISIBLE) {
                // An invisible popup would still intercept taps over the title/composer.
                popup.dismiss()
                return
            }
            // PopupWindow positions use window coordinates; pointer/layout use screen coordinates.
            val screen = IntArray(2)
            val window = IntArray(2)
            view.rootView.getLocationOnScreen(screen)
            view.rootView.getLocationInWindow(window)
            val xInWindow = (position.first - anchorX - screen[0] + window[0]).roundToInt()
            val yInWindow = (y - screen[1] + window[1]).roundToInt()
            if (popup.isShowing) popup.update(xInWindow, yInWindow, -1, -1)
            else if (view.isAttachedToWindow) popup.showAtLocation(view, Gravity.NO_GRAVITY, xInWindow, yInWindow)
        }
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> beginDrag(start, event.rawX, event.rawY)
                MotionEvent.ACTION_MOVE -> moveDrag(event.rawX, event.rawY)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
                MotionEvent.ACTION_POINTER_DOWN -> endDrag()
            }
            return true
        }
    }
}

internal fun selectionWord(text: String, offset: Int): MarkdownSelection {
    if (text.isEmpty()) return MarkdownSelection(0, 0)
    val index = offset.coerceIn(0, text.lastIndex)
    val iterator = BreakIterator.getWordInstance().apply { setText(text) }
    val start = iterator.preceding(index + 1).takeIf { it != BreakIterator.DONE } ?: 0
    val end = iterator.following(index).takeIf { it != BreakIterator.DONE } ?: text.length
    return MarkdownSelection(start, end)
}
