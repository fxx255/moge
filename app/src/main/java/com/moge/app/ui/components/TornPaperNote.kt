package com.moge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme
import kotlin.math.ceil

/** 撕下的纸条：顶部装订孔、轻阴影与不规则底边，内容避开两端装饰。 */
@Composable
internal fun TornPaperNote(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val paper = MogeTheme.paper
    Column(
        modifier
            .shadow(2.dp, TornPaperShape, clip = false)
            .background(paper.tornNote, TornPaperShape)
            .clip(TornPaperShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .drawBehind {
                val inset = 14.dp.toPx()
                val spacing = 9.dp.toPx()
                val hole = Size(2.dp.toPx(), 2.dp.toPx())
                var x = inset
                while (x + hole.width <= size.width - inset) {
                    drawOval(paper.onStickyNote.copy(alpha = .28f), Offset(x, 8.dp.toPx()), hole)
                    x += spacing
                }
            }
            .padding(start = 14.dp, end = 14.dp, top = 24.dp, bottom = 23.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

private object TornPaperShape : Shape {
    private val tearDepths = floatArrayOf(.25f, .8f, .1f, .6f, 0f, .9f, .35f, .7f, .15f, 1f, .4f, .75f, .2f)

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val corner = with(density) { 2.dp.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        // A fixed depth keeps long formula/photo notes from losing content to the tear.
        val depth = with(density) { 6.dp.toPx() }.coerceAtMost(size.height / 4f)
        val segments = ceil(size.width / with(density) { 10.dp.toPx() }).toInt().coerceAtLeast(1)
        return Outline.Generic(Path().apply {
            moveTo(corner, 0f)
            lineTo(size.width - corner, 0f)
            quadraticTo(size.width, 0f, size.width, corner)
            for (i in 0..segments) {
                lineTo(size.width * (1f - i.toFloat() / segments), size.height - depth * tearDepths[i % tearDepths.size])
            }
            lineTo(0f, corner)
            quadraticTo(0f, 0f, corner, 0f)
            close()
        })
    }
}
