package com.moge.app.ui.markdown

import android.graphics.Paint
import android.text.style.LineHeightSpan
import android.text.style.UpdateLayout
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.BlockHandlerDef
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.BlockQuoteSpan
import io.noties.markwon.core.spans.BulletListItemSpan
import io.noties.markwon.core.spans.OrderedListItemSpan
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import kotlin.math.roundToInt

/** Lists keep the same paragraph geometry when their spans are updated in a live text buffer. */
internal class MarkdownListLayoutPlugin : AbstractMarkwonPlugin() {
    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(ListItem::class.java) { configuration, props ->
            val theme = configuration.theme()
            if (CoreProps.LIST_ITEM_TYPE.require(props) == CoreProps.ListItemType.BULLET) {
                ReflowingBulletSpan(theme, CoreProps.BULLET_LIST_ITEM_LEVEL.require(props))
            } else {
                ReflowingOrderedSpan(theme, "${CoreProps.ORDERED_LIST_ITEM_NUMBER.require(props)}.\u00a0")
            }
        }
        builder.setFactory(BlockQuote::class.java) { configuration, _ ->
            ReflowingQuoteSpan(configuration.theme())
        }
    }

    override fun configureVisitor(builder: MarkwonVisitor.Builder) {
        builder.blockHandler(object : BlockHandlerDef() {
            override fun blockStart(visitor: MarkwonVisitor, node: Node) {
                super.blockStart(visitor, node)
                val item = node.parent as? ListItem ?: return
                if (!item.isLoose()) return
                val text = visitor.builder()
                val end = text.length
                if (end >= 2 && text[end - 1] == '\n' && text[end - 2] == '\n') {
                    text.setSpan(ListParagraphGapSpan(), end - 1, end)
                }
            }

            override fun blockEnd(visitor: MarkwonVisitor, node: Node) {
                super.blockEnd(visitor, node)
                val item = node.parent as? ListItem ?: return
                // Insert the separator before the next item's span starts, so its
                // marker stays beside its first text line rather than on the gap.
                if (item.isLoose() && node.next == null && item.next != null) {
                    visitor.ensureNewLine()
                    visitor.forceNewLine()
                }
            }
        })
    }
}

private fun ListItem.isLoose() = when (val list = parent) {
    is OrderedList -> !list.isTight
    is BulletList -> !list.isTight
    else -> false
}

// LeadingMarginSpan alone does not notify DynamicLayout when added, removed or
// extended. UpdateLayout makes Android reflow the affected paragraphs, including
// unchanged text whose indentation has changed. Keep the Markwon subclasses so
// list-number premeasurement and marker/quote drawing still find their own spans.
private class ReflowingOrderedSpan(theme: MarkwonTheme, number: String) :
    OrderedListItemSpan(theme, number), UpdateLayout

private class ReflowingBulletSpan(theme: MarkwonTheme, level: Int) :
    BulletListItemSpan(theme, level), UpdateLayout

private class ReflowingQuoteSpan(theme: MarkwonTheme) : BlockQuoteSpan(theme), UpdateLayout

private class ListParagraphGapSpan : LineHeightSpan, UpdateLayout {
    override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int,
        v: Int, fm: Paint.FontMetricsInt) {
        val gap = ((fm.descent - fm.ascent) * 0.4f).roundToInt().coerceAtLeast(1)
        fm.ascent = 0
        fm.top = 0
        fm.descent = gap
        fm.bottom = gap
    }
}
