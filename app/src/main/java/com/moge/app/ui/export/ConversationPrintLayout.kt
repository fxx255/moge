package com.moge.app.ui.export

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.TextView
import com.moge.app.data.prefs.ListMarkerSettings
import com.moge.app.ui.markdown.createMarkdownTextView
import io.noties.markwon.Markwon
import kotlin.math.roundToInt

/** Physical print sizes in points, independent of screen density, UI scale and PNG styling. */
internal object ConversationPrintLayout {
    const val WIDTH_PT = 595
    const val HEIGHT_PT = 842
    private const val UNITS_PER_PT = ExportLimits.WIDTH.toFloat() / WIDTH_PT
    fun pt(points: Float): Int = (points * UNITS_PER_PT).roundToInt()
    val height = pt(HEIGHT_PT.toFloat())
    val margin = pt(30f)
    val footer = pt(12f)
    val gap = pt(2.5f)
    val cellPadding = pt(3f)
    val bodySize = 10.5f * UNITS_PER_PT
    val labelSize = 11.5f * UNITS_PER_PT
    val titleSize = 14f * UNITS_PER_PT
    val captionSize = 8.5f * UNITS_PER_PT
}

/** Parsing needs a renderer per font size, not per table cell or paragraph. The document owns
 * the parsers; only the currently laid out page's TextViews are retained for drawing. */
internal class PrintTextLayout(private val context: Context, private val typeface: Typeface,
    private val listMarkers: ListMarkerSettings) {
    private val parsers = mutableMapOf<Float, Markwon>()

    fun view(size: Float, markdown: Boolean): TextView = TextView(context).apply {
        typeface = this@PrintTextLayout.typeface
        setTextColor(Color.BLACK)
        setLinkTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
        if (markdown) tag = parsers.getOrPut(size) {
            createMarkdownTextView(context, Color.BLACK, Color.BLACK, selectable = false, fontSizePx = size,
                bodyTypeface = typeface, markerColor = Color.BLACK, listMarkers = listMarkers).tag as Markwon
        }
    }
}
