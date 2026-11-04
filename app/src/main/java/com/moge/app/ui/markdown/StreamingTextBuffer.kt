package com.moge.app.ui.markdown

import android.text.Spannable
import android.text.SpannableStringBuilder

/** TextView's DynamicLayout can reflow an appended literal suffix without measuring old paragraphs. */
internal object StreamingTextBufferFactory : Spannable.Factory() {
    override fun newSpannable(source: CharSequence): Spannable = SpannableStringBuilder(source)
}
