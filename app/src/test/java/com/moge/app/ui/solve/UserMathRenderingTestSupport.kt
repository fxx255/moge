package com.moge.app.ui.solve

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.moge.app.ui.markdown.BaselineLatexSpan
import io.noties.markwon.Markwon
import io.noties.markwon.image.AsyncDrawableScheduler
import org.junit.Assert.*
import ru.noties.jlatexmath.JLatexMathDrawable

internal fun nativeMathViews(root: View): List<TextView> = when (root) {
    is TextView -> if (root.tag is Markwon) listOf(root) else emptyList()
    is ViewGroup -> (0 until root.childCount).flatMap { nativeMathViews(root.getChildAt(it)) }
    else -> emptyList()
}

internal fun nativeMathSpans(view: TextView): List<BaselineLatexSpan> {
    val text = view.text as? Spanned ?: return emptyList()
    return text.getSpans(0, text.length, BaselineLatexSpan::class.java).toList().sortedBy { text.getSpanStart(it) }
}

/** Exercise native JLatexMath, not just a Markdown string or fake drawable. */
internal fun assertNativeMathDraws(view: TextView) {
    AsyncDrawableScheduler.unschedule(view)
    val spans = nativeMathSpans(view)
    assertTrue("The actual Compose-hosted TextView must contain formula spans", spans.isNotEmpty())
    for (span in spans) {
        val native = JLatexMathDrawable.builder(span.drawable.destination).textSize(view.textSize).build()
        assertTrue(native.intrinsicWidth > 0 && native.intrinsicHeight > 0)
        span.drawable.initWithKnownDimensions(view.width.coerceAtLeast(1), view.textSize)
        span.drawable.setResult(native)
        val bitmap = Bitmap.createBitmap(native.intrinsicWidth, native.intrinsicHeight, Bitmap.Config.ARGB_8888)
        native.setBounds(0, 0, bitmap.width, bitmap.height)
        native.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Native formula must draw visible pixels", pixels.any { it ushr 24 != 0 })
        bitmap.recycle()
    }
}
