package com.moge.app.ui.markdown

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.text.Selection
import android.text.Spannable
import android.view.View
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SelectionAppearanceTest {
    private val themes = listOf(
        Triple("paper", Color.rgb(31, 58, 138), Color.WHITE),
        Triple("chalk", Color.rgb(242, 214, 117), Color.rgb(36, 51, 45)),
    )

    @Test fun `handles have mirrored square anchors and opaque theme colored round bodies`() {
        for ((_, accent, _) in themes) for (density in listOf(1f, 2f)) {
            val start = SelectionHandleDrawable(accent, true, density)
            val end = SelectionHandleDrawable(accent, false, density)
            val size = (28 * density).toInt()
            val a = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            try {
                start.setBounds(0, 0, size, size); start.draw(Canvas(a))
                end.setBounds(0, 0, size, size); end.draw(Canvas(b))
                assertEquals(accent, a.getPixel(size - 2, 1))
                assertEquals(Color.TRANSPARENT, a.getPixel(1, 1))
                assertEquals(accent, a.getPixel(size / 2, size / 2))
                for (y in 0 until size) for (x in 0 until size) {
                    val difference = kotlin.math.abs(Color.alpha(a.getPixel(x, y)) - Color.alpha(b.getPixel(size - 1 - x, y)))
                    assertTrue("Mirrored contours may differ only by subpixel antialiasing", difference <= 32)
                }
            } finally { a.recycle(); b.recycle() }
        }
    }

    private fun attachedBody(activity: Activity, accent: Int, surface: Int, host: SelectionScrollHost? = null): TextView {
        val ink = if (surface == Color.WHITE) Color.BLACK else Color.WHITE
        return createMarkdownTextView(activity, ink, accent, fontSizePx = 26f,
            selectionSurfaceColor = surface, selectionScrollHost = host).also {
            it.text = android.text.SpannableString("长按选择这段文字，然后拖动水滴形手柄。\n放大镜跟随选区移动，方便准确调整复制范围。\n\n\n\n\n\n\n\n")
            it.setBackgroundColor(surface)
            activity.setContentView(it)
            it.measure(View.MeasureSpec.makeMeasureSpec(760, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(520, View.MeasureSpec.EXACTLY))
            it.layout(0, 0, 760, 520)
        }
    }

    @Test fun `lens enlarges selected text above the finger with themed border and dismisses`() {
        for ((name, accent, surface) in themes) {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val body = attachedBody(controller.get(), accent, surface)
            val magnifier = SelectionMagnifier(body, accent, surface)
            try {
                body.requestFocus()
                Selection.setSelection(body.text as Spannable, 2, 8)
                val lineY = (body.layout.getLineTop(0) + body.layout.getLineBottom(0)) / 2f
                val x = body.totalPaddingLeft + body.layout.getPrimaryHorizontal(8)
                magnifier.show(x, lineY, 250f, RectF(0f, 0f, 760f, 520f))
                assertTrue(magnifier.isShowing)
                assertTrue(magnifier.screenBounds.bottom < 250f)
                val popup = requireNotNull(shadowOf(RuntimeEnvironment.getApplication()).latestPopupWindow)
                assertFalse("The magnifier must never intercept handle events", popup.isTouchable)
                val lens = popup.contentView
                lens.measure(View.MeasureSpec.makeMeasureSpec(popup.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(popup.height, View.MeasureSpec.EXACTLY))
                lens.layout(0, 0, popup.width, popup.height)
                val bitmap = Bitmap.createBitmap(popup.width, popup.height, Bitmap.Config.ARGB_8888)
                try {
                    lens.draw(Canvas(bitmap))
                    val ink = body.currentTextColor
                    var inkPixels = 0
                    var accentPixels = 0
                    val rim = androidx.core.graphics.ColorUtils.compositeColors(
                        androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 100), surface)
                    for (y in 0 until bitmap.height) for (pixelX in 0 until bitmap.width) {
                        val pixel = bitmap.getPixel(pixelX, y)
                        if (pixel == ink) inkPixels++
                        if (Color.alpha(pixel) > 0 && listOf(accent, rim).any { expected ->
                            kotlin.math.abs(Color.red(pixel) - Color.red(expected)) < 18 &&
                                kotlin.math.abs(Color.green(pixel) - Color.green(expected)) < 18 &&
                                kotlin.math.abs(Color.blue(pixel) - Color.blue(expected)) < 18
                        }) accentPixels++
                    }
                    assertTrue("The lens must draw the underlying text", inkPixels > 20)
                    assertTrue("The lens border must follow the active theme", accentPixels > 20)
                    if (System.getenv("SELECTION_EXPORT_SAMPLES") == "1") {
                        val preview = Bitmap.createBitmap(760, 240, Bitmap.Config.ARGB_8888)
                        try {
                            val canvas = Canvas(preview)
                            canvas.drawColor(surface)
                            canvas.save(); canvas.translate(0f, 150f); body.draw(canvas); canvas.restore()
                            canvas.drawBitmap(bitmap, x - bitmap.width / 2f, 30f, null)
                            for (start in listOf(true, false)) {
                                val drawable = SelectionHandleDrawable(accent, start, 1f)
                                val anchor = body.totalPaddingLeft + body.layout.getPrimaryHorizontal(if (start) 2 else 8)
                                val left = (if (start) anchor - 28 else anchor).toInt()
                                val top = 150 + body.layout.getLineBottom(0)
                                drawable.setBounds(left, top, left + 28, top + 28)
                                drawable.draw(canvas)
                            }
                            val file = File("build/selection-samples/$name.png").apply { requireNotNull(parentFile).mkdirs() }
                            file.outputStream().use { preview.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        } finally { preview.recycle() }
                    }
                } finally { bitmap.recycle() }
                magnifier.dismiss()
                assertFalse(magnifier.isShowing)
                // Reopening at the same size must recreate the released glass backdrop.
                magnifier.show(x, lineY, 250f, RectF(0f, 0f, 760f, 520f))
                assertTrue(magnifier.isShowing)
            } finally { magnifier.dismiss(); controller.pause().stop().destroy() }
        }
    }

    @Test fun `lens stays inside narrow fractional viewport and grows for large fonts`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val body = attachedBody(controller.get(), Color.BLUE, Color.WHITE)
        val magnifier = SelectionMagnifier(body, Color.BLUE, Color.WHITE)
        try {
            body.textSize = 40f
            val viewport = RectF(12f, 24f, 102.5f, 300f)
            magnifier.show(750f, 220f, 280f, viewport)
            assertTrue(magnifier.isShowing)
            assertTrue(viewport.contains(magnifier.screenBounds))
            assertTrue(magnifier.screenBounds.height() > 56f * body.resources.displayMetrics.density)
            controller.pause().stop().destroy()
            magnifier.show(10f, 10f, 30f, viewport)
            assertFalse("A detached answer cannot retain the lens", magnifier.isShowing)
        } finally { magnifier.dismiss() }
    }

    @Test fun `lens samples the exact dragged point rather than the line midpoint`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val body = attachedBody(controller.get(), Color.BLUE, Color.WHITE)
        val magnifier = SelectionMagnifier(body, Color.BLUE, Color.WHITE)
        try {
            val viewport = RectF(0f, 0f, 760f, 520f)
            magnifier.show(73.25f, 19.5f, 220f, viewport)
            assertEquals(73.25f, magnifier.sourcePoint.first, 0.001f)
            assertEquals(19.5f, magnifier.sourcePoint.second, 0.001f)
            assertTrue("The lens belongs above the current finger position", magnifier.screenBounds.bottom <= 220f)
        } finally {
            magnifier.dismiss()
            controller.pause().stop().destroy()
        }
    }

    @Test fun `dismissing a question selection by tapping cannot also start editing`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        val host = SelectionScrollHost(scope, 1f) { _, _ -> }
        val body = attachedBody(activity.get(), Color.BLUE, Color.WHITE, host)
        host.viewport = RectF(0f, 0f, 760f, 520f)
        var edits = 0
        setMarkdownTextTap(body) { edits++ }
        try {
            val selection = requireNotNull(markdownSelectionController(body))
            selection.begin(2, 8)
            val time = android.os.SystemClock.uptimeMillis()
            for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                val event = android.view.MotionEvent.obtain(time, time + 16, action, 100f, 16f, 0)
                try { body.dispatchTouchEvent(event) } finally { event.recycle() }
            }
            assertFalse(selection.selecting)
            assertEquals(0, edits)
        } finally { host.dispose(); activity.pause().stop().destroy() }
    }
}
