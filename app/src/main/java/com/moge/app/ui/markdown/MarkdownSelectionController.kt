package com.moge.app.ui.markdown

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
import android.view.ViewTreeObserver
import android.widget.PopupWindow
import android.widget.TextView
import java.text.BreakIterator
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
    private val location = IntArray(2)
    private val startHandle by lazy { Handle(true) }
    private val endHandle by lazy { Handle(false) }

    fun touch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // A new content gesture dismisses the old selection; the list can
            // then handle ordinary swipes without fighting an active scroll job.
            if (active) finish()
            lastTouchX = event.x
            lastTouchY = event.y
            selectionStartedDuringTouch = false
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
        val word = selectionWord(view.text.toString(), offset)
        begin(word.start, word.end)
        selectionStartedDuringTouch = true
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    internal fun begin(start: Int, end: Int) {
        if (start == end || view.text !is Spannable) return
        host.claim(this)
        active = true
        view.requestFocus()
        Selection.setSelection(view.text as Spannable, start, end)
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
        actionMode = null
        host.release(this)
        if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(this)
        startHandle.popup.dismiss()
        endHandle.popup.dismiss()
        (view.text as? Spannable)?.let { Selection.removeSelection(it) }
    }

    override fun onPreDraw(): Boolean {
        if (!view.isAttachedToWindow || !view.hasSelection() || !view.isShown) finish()
        else {
            updateHandles()
            if (dragging) actionMode?.hide(1_000) else actionMode?.invalidateContentRect()
        }
        return true
    }

    private fun endpoint(start: Boolean): Pair<Float, Float> {
        val layout = view.layout ?: return 0f to 0f
        val offset = (if (start) minOf(view.selectionStart, view.selectionEnd)
            else maxOf(view.selectionStart, view.selectionEnd)).coerceIn(0, view.text.length)
        val line = layout.getLineForOffset(offset)
        view.getLocationOnScreen(location)
        return (location[0] + view.totalPaddingLeft - view.scrollX + layout.getPrimaryHorizontal(offset)) to
            (location[1] + view.totalPaddingTop - view.scrollY + layout.getLineBottom(line)).toFloat()
    }

    private fun updateHandles() {
        if (!active || view.layout == null) return
        startHandle.place(endpoint(true))
        endHandle.place(endpoint(false))
    }

    internal fun beginDrag(start: Boolean, x: Float, y: Float) {
        if (!active) return
        dragStart = start
        fixedOffset = if (start) maxOf(view.selectionStart, view.selectionEnd)
            else minOf(view.selectionStart, view.selectionEnd)
        val position = endpoint(start)
        fingerOffsetX = x - position.first
        fingerOffsetY = y - position.second + view.lineHeight / 2f
        pointerX = x
        pointerY = y
        dragging = true
        actionMode?.hide(1_000)
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
        host.stopScrolling()
        actionMode?.hide(0)
        actionMode?.invalidateContentRect()
        updateHandles()
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
        val offset = view.getOffsetForPosition(pointerX - fingerOffsetX - location[0], y - location[1])
        val text = view.text as? Spannable ?: return
        // Handles may shrink the range, but never cross or collapse it.
        if (dragStart) Selection.setSelection(text, offset.coerceIn(0, (fixedOffset - 1).coerceAtLeast(0)), fixedOffset)
        else Selection.setSelection(text, fixedOffset, offset.coerceIn((fixedOffset + 1).coerceAtMost(text.length), text.length))
        updateHandles()
    }

    private inner class Handle(private val start: Boolean) : View(view.context) {
        private val size = (48f * host.density).roundToInt()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = handleColor }
        val popup = PopupWindow(this, size, size, false).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isClippingEnabled = false
            isOutsideTouchable = false
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        }
        init {
            contentDescription = if (start) "选区起点" else "选区终点"
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        override fun onDraw(canvas: Canvas) {
            val radius = 7f * host.density
            canvas.drawRect(width / 2f - host.density, 0f, width / 2f + host.density, radius * 2, paint)
            canvas.drawCircle(width / 2f, radius * 2, radius, paint)
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
            val xInWindow = (position.first - size / 2f - screen[0] + window[0]).roundToInt()
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
