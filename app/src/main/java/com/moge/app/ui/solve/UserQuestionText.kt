package com.moge.app.ui.solve

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.moge.app.ui.theme.LocalAppTypeface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.moge.app.ui.markdown.MARKDOWN_TEXT_SIZE_SP
import com.moge.app.ui.markdown.LocalSelectionScrollHost
import com.moge.app.ui.markdown.createMarkdownTextView
import com.moge.app.ui.markdown.renderMarkdownIfChanged
import com.moge.app.ui.markdown.setMarkdownTextTap
import com.moge.app.ui.markdown.markdownSelectionController

/** All question text shares the same handles, including literal and unfinished input. */
@Composable
internal fun UserQuestionText(
    source: String,
    color: Color = MaterialTheme.colorScheme.onSurface,
    showSourceCopy: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val strings = rememberAppStrings()
    val math = remember(source) { userMathSource(source) }
    if (math == null) {
        UserTextBody(source, renderMath = false, color, onClick)
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            UserTextBody(if (math.incomplete) source else math.renderSource, renderMath = !math.incomplete, color, onClick)
            if (showSourceCopy) {
                val context = LocalContext.current
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(strings.text(R.string.ui_original_question_d63c4), source))
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = strings.text(R.string.ui_copy_original_question_33996), tint = color)
                    }
                }
            }
        }
    }
}

/**
 * Reuse the existing native view, renderer, render cache and failure fallback.
 * Use the bubble's actual width so shared formula wrapping and drawable fitting
 * keep the entire equation visible, including unsplittable integral products.
 */
@Composable
private fun UserTextBody(source: String, renderMath: Boolean, color: Color, onClick: (() -> Unit)?) {
    val textColor = color.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val selectionSurfaceColor = MaterialTheme.colorScheme.surface.toArgb()
    val selectionScrollHost = LocalSelectionScrollHost.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val bodyTypeface = LocalAppTypeface.current
    val localeTag = androidx.compose.ui.platform.LocalConfiguration.current.locales.toLanguageTags()
    val style = MaterialTheme.typography.bodyLarge
    val fontSizePx = with(density) { if (renderMath) MARKDOWN_TEXT_SIZE_SP.sp.toPx() else style.fontSize.toPx() }
    val lineHeightPx = with(density) { style.lineHeight.toPx() }
    val editLabel = rememberAppStrings().text(R.string.ui_edit_input_695fa)
    val width = if (renderMath) Modifier.fillMaxWidth() else Modifier
    BoxWithConstraints(width.clipToBounds()) {
        val viewportWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        key(bodyTypeface, localeTag, textColor, linkColor, selectionSurfaceColor, fontSizePx, lineHeightPx, selectionScrollHost, renderMath) {
            val view = remember {
                createMarkdownTextView(context, textColor, linkColor, fontSizePx = fontSizePx,
                    selectionScrollHost = selectionScrollHost, selectionSurfaceColor = selectionSurfaceColor, bodyTypeface = bodyTypeface)
                    .apply {
                        if (!renderMath) {
                            includeFontPadding = false
                            setPadding(0, 0, 0, 0)
                            setLineSpacing((lineHeightPx - paint.getFontMetricsInt(null)).coerceAtLeast(0f), 1f)
                        }
                    }
            }
            LaunchedEffect(view, source, viewportWidthPx) {
                withFrameNanos { }
                view.requestLayout()
            }
            AndroidView(
                modifier = width.semantics {
                    if (!renderMath) text = AnnotatedString(source)
                    if (onClick != null) onClick(editLabel) { view.performClick() }
                },
                factory = { view },
                update = {
                    if (renderMath) it.contentDescription = source
                    // A long press or a tap ending an existing selection is a selection
                    // gesture; only an ordinary tap should start editing the old input.
                    setMarkdownTextTap(it, onClick)
                    if (renderMath) renderMarkdownIfChanged(it, source, viewportWidthPx, textColor, linkColor)
                    // Keep literal input byte-for-byte, and never replace its
                    // Spannable during selection or an unrelated recomposition.
                    else if (it.text.toString() != source) {
                        markdownSelectionController(it)?.finish()
                        it.text = source
                    }
                },
            )
        }
    }
}
