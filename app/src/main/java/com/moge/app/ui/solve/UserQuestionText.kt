package com.moge.app.ui.solve

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.moge.app.ui.components.excludePageSwipe
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.moge.app.ui.markdown.MARKDOWN_TEXT_SIZE_SP
import com.moge.app.ui.markdown.createMarkdownTextView
import com.moge.app.ui.markdown.formulaWidthMeasurer
import com.moge.app.ui.markdown.renderMarkdownIfChanged
import com.moge.app.ui.markdown.setMarkdownTextTap

/** Both first questions and follow-ups use the same native math path. */
@Composable
internal fun UserQuestionText(source: String, color: Color = MaterialTheme.colorScheme.onSurface, onClick: (() -> Unit)? = null) {
    val click = if (onClick != null) Modifier.clickable(onClickLabel = "修改输入", onClick = onClick) else Modifier
    val math = remember(source) { userMathSource(source) }
    if (math == null) {
        SelectionContainer {
            Text(source, style = MaterialTheme.typography.bodyLarge, color = color, modifier = click)
        }
    } else {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (math.incomplete) SelectionContainer {
                Text(source, style = MaterialTheme.typography.bodyLarge, color = color, modifier = click)
            }
            else UserMathBody(math, color, onClick)
            val context = LocalContext.current
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("题目原文", source))
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制题目原文", tint = color)
                }
            }
        }
    }
}

/**
 * Reuse the existing native view, renderer, render cache and failure fallback.
 * Measure formulas with its own width measurer, then give the scrollable view
 * enough width instead of clipping equations to the input/card width.
 */
@Composable
private fun UserMathBody(math: UserMathSource, color: Color, onClick: (() -> Unit)?) {
    val textColor = color.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val context = LocalContext.current
    val density = LocalDensity.current
    val fontSizePx = with(density) { MARKDOWN_TEXT_SIZE_SP.sp.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()) {
        val viewportWidthPx = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        key(textColor, linkColor, fontSizePx) {
            val view = remember {
                createMarkdownTextView(context, textColor, linkColor, fontSizePx = fontSizePx)
            }
            val renderWidthPx = remember(math, viewportWidthPx) {
                val measure = formulaWidthMeasurer(view)
                // The shared measurer uses resource sp; account for Compose font-scale overrides too.
                val scale = fontSizePx / (MARKDOWN_TEXT_SIZE_SP * view.resources.displayMetrics.scaledDensity)
                val formulaWidth = math.formulas.maxOfOrNull { (measure(it) * scale).toInt() } ?: 0
                val padding = with(density) { 32.dp.roundToPx() }
                (formulaWidth.toLong() + padding)
                    .coerceIn(viewportWidthPx.toLong(), maxOf(viewportWidthPx, with(density) { 4096.dp.roundToPx() }).toLong())
                    .toInt()
            }
            LaunchedEffect(view, math.renderSource, renderWidthPx) {
                withFrameNanos { }
                view.requestLayout()
            }
            Box(Modifier.fillMaxWidth().excludePageSwipe().horizontalScroll(rememberScrollState()).clipToBounds()) {
                AndroidView(
                    modifier = Modifier.width(with(density) { renderWidthPx.toDp() }),
                    factory = { view },
                    update = {
                        it.contentDescription = math.renderSource
                        // A long press or a tap ending an existing selection is a selection
                        // gesture; only an ordinary tap should start editing the old input.
                        setMarkdownTextTap(it, onClick)
                        renderMarkdownIfChanged(it, math.renderSource, renderWidthPx, textColor, linkColor)
                    },
                )
            }
        }
    }
}
