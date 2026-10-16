package com.moge.app.ui.markdown

import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.withClip
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * Draw the native text (including formula spans and selection) in a separate, non-touchable lens.
 * This also works on API 26/27 and does not retain a full-answer bitmap or depend on a Surface copy.
 */
internal class SelectionMagnifier(
    private val source: TextView,
    private val accent: Int,
    private val surface: Int,
) {
    private val density = source.resources.displayMetrics.density
    private val lens = Lens()
    private val popup = PopupWindow(lens, 1, 1, false).apply {
        isTouchable = false
        isFocusable = false
        isOutsideTouchable = false
        isClippingEnabled = false
        inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
        setBackgroundDrawable(GradientDrawable().apply {
            setColor(Color.argb(210, Color.red(surface), Color.green(surface), Color.blue(surface)))
            cornerRadius = 48f * density
        })
        elevation = 6f * density
    }
    val isShowing: Boolean get() = popup.isShowing
    internal var sourcePoint = 0f to 0f
        private set
    internal var screenBounds = RectF()
        private set
    private var sampledRow: SelectionEndpoint? = null
    private var zoom = ZOOM

    fun show(pointerX: Float, pointerY: Float, viewport: RectF, selectionOffset: Int, start: Boolean) {
        if (!source.isAttachedToWindow || !source.isShown || viewport.width() <= 0 || viewport.height() <= 0) {
            dismiss()
            return
        }
        val row = selectionEndpoint(source, selectionOffset, start) ?: run { dismiss(); return }
        sampledRow = row
        sourcePoint = row.x to row.centerY
        val width = minOf(152f * density, viewport.width()).toInt().coerceAtLeast(1)
        val rowHeight = row.bottom - row.top
        val height = maxOf(56f * density, rowHeight * ZOOM + 16f * density)
            .coerceAtMost(minOf(96f * density, viewport.height())).roundToInt().coerceAtLeast(1)
        // Fit the whole active row even with large text or a tall formula span.
        zoom = minOf(ZOOM, (height - 16f * density).coerceAtLeast(1f) / rowHeight)
        val centerX = pointerX.coerceIn(viewport.left + width / 2f, viewport.right - width / 2f)
        val gap = 24f * density
        val above = pointerY - gap - height
        // At the upper auto-scroll edge the lens must move below the finger,
        // rather than being pinned over the text the user is trying to select.
        val top = (if (above >= viewport.top) above else pointerY + gap)
            .coerceIn(viewport.top, maxOf(viewport.top, viewport.bottom - height))
        screenBounds = RectF(centerX - width / 2f, top, centerX + width / 2f, top + height)
        val rootScreen = IntArray(2).also { source.rootView.getLocationOnScreen(it) }
        val rootWindow = IntArray(2).also { source.rootView.getLocationInWindow(it) }
        val left = (screenBounds.left - rootScreen[0] + rootWindow[0]).roundToInt()
        val windowTop = (top - rootScreen[1] + rootWindow[1]).roundToInt()
        lens.prepare(width, height)
        if (popup.isShowing) popup.update(left, windowTop, width, height)
        else {
            popup.width = width
            popup.height = height
            popup.showAtLocation(source, Gravity.NO_GRAVITY, left, windowTop)
        }
        lens.invalidate()
    }

    fun dismiss() { popup.dismiss(); lens.release() }

    private inner class Lens : View(source.context) {
        private val path = Path()
        private val bounds = RectF()
        private val reflection = RectF()
        private val selectionPath = Path()
        private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val location = IntArray(2)
        private var backdrop: Bitmap? = null
        private var backdropCanvas: Canvas? = null
        private var preparedWidth = 0
        private var preparedHeight = 0
        private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accent
            style = Paint.Style.STROKE
            strokeWidth = 1.75f * density
        }
        private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
        private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
        private val highlight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 34
        }
        private val fillTop = Color.argb(116, Color.red(surface), Color.green(surface), Color.blue(surface))
        private val fillBottom = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(surface, accent, .09f), 80)
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            prepare(w, h)
        }

        fun prepare(w: Int, h: Int) {
            if (w == preparedWidth && h == preparedHeight && backdrop != null) return
            release()
            if (w <= 0 || h <= 0) return
            preparedWidth = w
            preparedHeight = h
            // Downsample only this small lens region. Filtering it back to size
            // supplies a soft backdrop on API 26+, without copying a whole answer.
            val sample = 8f * density
            backdrop = Bitmap.createBitmap((w / sample).roundToInt().coerceAtLeast(1),
                (h / sample).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            backdropCanvas = Canvas(requireNotNull(backdrop))
            glass.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), fillTop, fillBottom, Shader.TileMode.CLAMP)
            gloss.shader = LinearGradient(0f, 0f, 0f, h * .44f,
                Color.argb(92, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            reflection.set(w * .06f, h * .08f, w * .52f, h * .28f)
        }

        fun release() {
            backdropCanvas = null
            backdrop?.recycle()
            backdrop = null
            preparedWidth = 0
            preparedHeight = 0
        }

        private fun drawBackdrop(canvas: Canvas) {
            val bitmap = backdrop ?: return
            val sampleCanvas = backdropCanvas ?: return
            source.getLocationOnScreen(location)
            bitmap.eraseColor(surface)
            val saved = sampleCanvas.save()
            sampleCanvas.scale(bitmap.width / width.toFloat(), bitmap.height / height.toFloat())
            sampleCanvas.translate(location[0] - screenBounds.left, location[1] - screenBounds.top)
            source.draw(sampleCanvas)
            sampleCanvas.restoreToCount(saved)
            canvas.drawBitmap(bitmap, null, bounds, backdropPaint)
        }

        private fun drawText(canvas: Canvas) {
            val layout = source.layout ?: return
            val row = sampledRow ?: return
            // The sample follows the caret, while the window follows the hand.
            // Clip before translating into Layout space so adjacent rows cannot
            // intrude into the lens when the answer uses generous line spacing.
            canvas.clipRect(0f, row.top, source.width.toFloat(), row.bottom)
            canvas.translate(source.totalPaddingLeft - source.scrollX.toFloat(), source.totalPaddingTop - source.scrollY.toFloat())
            val start = source.selectionStart
            val end = source.selectionEnd
            selectionPath.reset()
            if (start >= 0 && end >= 0 && start != end) layout.getSelectionPath(minOf(start, end), maxOf(start, end), selectionPath)
            selectionPaint.color = ColorUtils.setAlphaComponent(accent, 96)
            // Render text/spans above the glass. Drawing the TextView background
            // here would turn the lens opaque and hide all of its glass treatment.
            // Draw explicitly: Layout's optional highlight path is not rendered
            // consistently without the TextView editor's focus/selection state.
            canvas.drawPath(selectionPath, selectionPaint)
            layout.draw(canvas)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = border.strokeWidth / 2f
            bounds.set(inset, inset, width - inset, height - inset)
            path.reset()
            path.addRoundRect(bounds, height / 2f, height / 2f, Path.Direction.CW)
            canvas.withClip(path) {
                drawBackdrop(this)
                drawRect(bounds, glass)
                drawRect(bounds, gloss)
                drawOval(reflection, highlight)
                translate(bounds.centerX(), bounds.centerY())
                scale(zoom, zoom)
                translate(-sourcePoint.first, -sourcePoint.second)
                drawText(this)
            }
            // A faint inner rim is the visible glass edge; the accent outline
            // remains the theme-colored affordance users can track while dragging.
            border.alpha = 100
            canvas.drawPath(path, border)
            border.alpha = 255
        }
    }

    private companion object { const val ZOOM = 1.5f }
}
