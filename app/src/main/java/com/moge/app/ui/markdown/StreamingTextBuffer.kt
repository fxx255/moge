package com.moge.app.ui.markdown

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.NoCopySpan
import android.text.Spanned
import android.text.SpanWatcher
import android.text.TextWatcher
import android.widget.TextView
import java.util.Collections
import java.util.IdentityHashMap

/** TextView's DynamicLayout can reflow an appended literal suffix without measuring old paragraphs. */
internal object StreamingTextBufferFactory : Spannable.Factory() {
    override fun newSpannable(source: CharSequence): Spannable = SpannableStringBuilder(source)
}

/**
 * Update the changed paragraph while keeping native layout, selection and preceding span objects.
 * Call only for appended render source: arbitrary edits can change a span's meaning without
 * changing its class/range (for example a link destination), and must use the normal setter.
 */
internal fun updateStreamingTextBuffer(view: TextView, next: Spanned): Boolean {
    val buffer = view.text as? SpannableStringBuilder ?: return false
    if (buffer.isEmpty()) return false
    val before = markdownTextSnapshot(view)
    val after = next.toString()
    val start = paragraphStart(buffer, before.commonPrefixWith(after).length)
    val oldSpans = buffer.getSpans(0, buffer.length, Any::class.java).filterNot(::isTextObserver)
    val newSpans = next.getSpans(0, next.length, Any::class.java).filterNot(::isTextObserver)

    val byRange = oldSpans.groupBy { spanRange(buffer, it) }.mapValues { ArrayDeque(it.value) }
    val retained = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    val replacements = newSpans.map { span ->
        val range = spanRange(next, span)
        val existing = byRange[range]?.removeLastOrNull()
        if (existing != null) retained += existing
        range to (existing ?: span)
    }

    view.beginBatchEdit()
    try {
        // Apply characters without copying a second set of spans. Reconcile styles
        // separately: a document-wide spacing span can grow without replacing all
        // preceding formula/selection objects or their text.
        if (before != after) buffer.replace(start, buffer.length, after, start, after.length)
        oldSpans.filterNot { it in retained }.forEach(buffer::removeSpan)
        for ((range, span) in replacements) {
            if (spanRange(buffer, span) != range)
                buffer.setSpan(span, range.start, range.end, range.flags)
        }
    } finally { view.endBatchEdit() }
    return true
}

private fun isTextObserver(span: Any): Boolean = span is NoCopySpan || span is TextWatcher || span is SpanWatcher

private data class SpanRange(val type: Class<*>, val start: Int, val end: Int, val flags: Int)

private fun spanRange(text: Spanned, span: Any) =
    SpanRange(span.javaClass, text.getSpanStart(span), text.getSpanEnd(span), text.getSpanFlags(span))

private fun paragraphStart(text: CharSequence, offset: Int): Int {
    var start = offset.coerceIn(0, text.length)
    while (start > 0 && text[start - 1] != '\n') start--
    return start
}
