package com.moge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moge.app.ui.theme.MogeTheme

/** 方格纸背景：只铺在页面最底层，格子随屏幕固定，不跟随内容滚动。 */
fun Modifier.gridPaper(
    background: Color,
    line: Color,
    cell: Dp = 24.dp,
    stroke: Dp = 0.5.dp,
): Modifier = this
    .background(background)
    .drawBehind {
        val step = cell.toPx()
        val width = stroke.toPx().coerceAtLeast(1f)
        var x = step
        while (x < size.width) {
            drawLine(line, Offset(x, 0f), Offset(x, size.height), width)
            x += step
        }
        var y = step
        while (y < size.height) {
            drawLine(line, Offset(0f, y), Offset(size.width, y), width)
            y += step
        }
    }

@Composable
fun GridPaper(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.gridPaper(
            background = MaterialTheme.colorScheme.background,
            line = MogeTheme.paper.grid,
        ),
        content = content,
    )
}

/**
 * 荧光笔涂抹：在内容下半部画一道略倾斜、两端不齐的色带。
 * 只画在背后，不改变布局尺寸。
 */
fun Modifier.highlighter(color: Color, coverage: Float = 0.46f): Modifier = drawBehind {
    val h = size.height * coverage
    val top = size.height - h - size.height * 0.06f
    val path = Path().apply {
        moveTo(-4.dp.toPx(), top + h * 0.18f)
        lineTo(size.width + 3.dp.toPx(), top)
        lineTo(size.width + 5.dp.toPx(), top + h * 0.86f)
        lineTo(-2.dp.toPx(), top + h)
        close()
    }
    drawPath(path, color)
}

/** 页面标题：衬线体 + 荧光笔下划。 */
@Composable
fun HighlightedTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = style,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = modifier.highlighter(MogeTheme.paper.highlighter).semantics { heading() },
    )
}

/** 半透明胶带：斜贴在照片或卡片的边角上。 */
@Composable
fun Tape(
    modifier: Modifier = Modifier,
    width: Dp = 56.dp,
    height: Dp = 18.dp,
    angle: Float = -8f,
) {
    val color = MogeTheme.paper.tape
    Box(
        modifier = modifier
            .rotate(angle)
            .drawBehind {
                // 两端锯齿，像手撕的胶带
                val teeth = 5
                val tooth = size.height / teeth
                val depth = 2.dp.toPx()
                val path = Path().apply {
                    moveTo(0f, 0f)
                    lineTo(size.width, 0f)
                    for (i in 1..teeth) {
                        lineTo(size.width - if (i % 2 == 1) depth else 0f, tooth * i)
                    }
                    lineTo(0f, size.height)
                    for (i in teeth - 1 downTo 0) {
                        lineTo(if (i % 2 == 1) depth else 0f, tooth * i)
                    }
                    close()
                }
                drawPath(path, color)
            }
            .size(width, height),
    )
}

/** 解答纸左侧的红色页边线；[progress] < 1 时只描到对应高度（生成中由上往下描）。 */
fun Modifier.marginLine(
    color: Color,
    inset: Dp = 28.dp,
    progress: Float = 1f,
): Modifier = drawBehind {
    val x = inset.toPx()
    val end = size.height * progress.coerceIn(0f, 1f)
    val w = 1.dp.toPx()
    drawLine(color, Offset(x, 0f), Offset(x, end), w)
    drawLine(color.copy(alpha = color.alpha * 0.55f), Offset(x + 3.dp.toPx(), 0f), Offset(x + 3.dp.toPx(), end), w)
}

/** 白纸卡片：1dp 墨线描边，不用阴影。 */
fun Modifier.paperCard(
    fill: Color,
    stroke: Color,
    radius: Dp = 6.dp,
): Modifier = this
    .background(fill, RoundedCornerShape(radius))
    .border(1.dp, stroke, RoundedCornerShape(radius))

/** 虚线框：草稿区用。 */
fun Modifier.dashedBorder(color: Color, radius: Dp = 6.dp, dash: Dp = 5.dp): Modifier = drawBehind {
    val r = radius.toPx()
    val w = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(w / 2, w / 2),
        size = Size(size.width - w, size.height - w),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        style = Stroke(
            width = w,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                floatArrayOf(dash.toPx(), dash.toPx() * 0.7f),
            ),
        ),
    )
}

/** 红笔印章：双线圆角框 + 粗体字，微微倾斜。 */
@Composable
fun Stamp(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MogeTheme.paper.stamp,
    angle: Float = -6f,
) {
    Box(
        modifier = modifier
            .rotate(angle)
            .border(1.5.dp, color, RoundedCornerShape(4.dp))
            .padding(2.dp)
            .border(0.75.dp, color.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
    }
}
