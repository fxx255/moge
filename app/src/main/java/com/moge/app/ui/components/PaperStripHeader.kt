package com.moge.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import com.moge.app.ui.theme.MogeTheme

internal val PaperHeaderMinHeight = 54.dp
internal val PaperHeaderContentGap = 20.dp
internal val PaperHeaderTopGap = 5.dp

@Composable
internal fun paperHeaderTitleStyle(): TextStyle =
    MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp)

/** Settings use one strip; stacked pages expose the lower edge toward their navigation side. */
enum class PaperHeaderStyle { Classic, Single, StackedLeft, StackedRight }

/** A narrow paper band whose cut ends stay outside the screen, with a reversible underlay. */
@Composable
internal fun PaperStripHeader(
    progress: () -> Float,
    modifier: Modifier = Modifier,
    underlayTilt: Float = 2f,
    showUnderlay: Boolean = true,
    targetProgress: Float = 0f,
    overlayZIndex: Float = 1f,
    active: Boolean? = null,
    content: @Composable () -> Unit,
) {
    val paper = MogeTheme.paper
    val line = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val shadow = Color.Black.copy(alpha = if (paper.isChalk) 0.18f else 0.1f)
    val navigation = LocalPaperNavigationScope.current
    val page = LocalPaperPageMotion.current
    val headerActive = active ?: page.active
    val placement = navigationPaperHeader(underlayTilt * (2f * targetProgress - 1f), showUnderlay,
        overlayZIndex, headerActive)
    val poseProgress = { navigation?.effectivePose()?.first?.let { ((it + 2f) / 4f).coerceIn(0f, 1f) }
        ?: progress().coerceIn(0f, 1f) }
    Box(modifier.testTag("paper-strip-header").padding(top = PaperHeaderTopGap)) {
        if (navigation == null && showUnderlay) Box(Modifier.matchParentSize().drawBehind {
            drawPaperUnderlay(size, underlayTilt * (2f * progress().coerceIn(0f, 1f) - 1f),
                paper.headerUnderlay, line)
        })
        Box(Modifier.fillMaxWidth().onGloballyPositioned {
            placement?.position = it.positionInRoot()
            placement?.size = Size(it.size.width.toFloat(), it.size.height.toFloat())
        }) {
            Box(Modifier.fillMaxWidth()
                .then(if (headerActive) Modifier else Modifier.clearAndSetSemantics {})
                .aboveMovingPages(true, overlayZIndex, headerInk = true, offsetX = page.headerOffsetX) {
                    if (!headerActive && page.active) 0f else page.headerAlpha()
                }
                .graphicsLayer { translationY = -paperHeaderLift(poseProgress()) }
                .then(if (navigation == null) Modifier.drawBehind {
                    drawPaperFront(size, poseProgress(), paper.headerPaper, line, shadow)
                } else Modifier)) { content() }
        }
    }
}

internal fun Density.paperHeaderLift(progress: Float): Float = 2.dp.toPx() * progress

internal fun DrawScope.drawPaperUnderlay(
    bounds: Size, tilt: Float, paper: Color, line: Color, alpha: Float = 1f,
) {
    val overhang = 64.dp.toPx()
    val extension = 2.dp.toPx()
    val top = 4.dp.toPx() - extension
    val height = bounds.height + 2 * extension
    val start = -overhang
    val end = bounds.width + overhang
    clipRect(left = start, top = -extension, right = end, bottom = bounds.height + overhang) {
        rotate(tilt, pivot = Offset(bounds.width / 2, bounds.height / 2)) {
            drawRect(paper.copy(alpha = paper.alpha * alpha), Offset(start, top), Size(end - start, height))
            drawLine(line.copy(alpha = line.alpha * alpha), Offset(start, top), Offset(end, top), 1.dp.toPx())
            drawLine(line.copy(alpha = line.alpha * alpha), Offset(start, top + height), Offset(end, top + height), 1.dp.toPx())
        }
    }
}

internal fun DrawScope.drawPaperFront(bounds: Size, progress: Float, paper: Color, line: Color, shadow: Color) {
    val overhang = 64.dp.toPx()
    val width = bounds.width + 2 * overhang
    val depth = (5f + 3f * progress).dp.toPx()
    drawRect(Brush.verticalGradient(listOf(shadow, Color.Transparent),
        startY = bounds.height, endY = bounds.height + depth),
        Offset(-overhang, bounds.height), Size(width, depth))
    drawRect(paper, Offset(-overhang, 0f), Size(width, bounds.height))
    drawLine(line, Offset(-overhang, 0f), Offset(bounds.width + overhang, 0f), 1.dp.toPx())
    drawLine(line, Offset(-overhang, bounds.height), Offset(bounds.width + overhang, bounds.height), 1.dp.toPx())
}
