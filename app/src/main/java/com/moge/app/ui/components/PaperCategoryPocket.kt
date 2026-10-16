package com.moge.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme

/** A raised pocket, an exposed paper insert and a front label share one drop target. */
@Composable
internal fun PaperCategoryPocket(name: String, highlighted: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val paper = MogeTheme.paper
    val stock = lerp(colors.surface, paper.tape.copy(alpha = 1f), .46f)
    val front = if (highlighted) lerp(stock, colors.primary, .22f) else stock
    val rear = lerp(stock, colors.onSurface, .12f)
    val insert = lerp(colors.surface, colors.onSurface, if (paper.isChalk) .80f else .02f)
    val edge = lerp(front, colors.onSurface, .28f)
    val seam = colors.onSurface.copy(alpha = .12f)
    Box(modifier.padding(horizontal = 3.dp, vertical = 5.dp).semantics { selected = highlighted }) {
        Box(Modifier.matchParentSize().padding(top = 12.dp)
            .shadow(4.dp, RoundedCornerShape(8.dp)).background(rear, RoundedCornerShape(8.dp)))
        Box(Modifier.matchParentSize().padding(start = 11.dp, end = 11.dp, top = 2.dp, bottom = 18.dp)
            .shadow(1.dp, RoundedCornerShape(4.dp)).background(insert, RoundedCornerShape(4.dp))
            .drawBehind {
                for (line in listOf(9.dp, 15.dp)) {
                    drawLine(seam, Offset(7.dp.toPx(), line.toPx()), Offset(size.width - 7.dp.toPx(), line.toPx()), 1.dp.toPx())
                }
            })
        Box(Modifier.matchParentSize().padding(top = 24.dp)
            .shadow(if (highlighted) 4.dp else 2.dp, PocketFrontShape)
            .background(Brush.verticalGradient(listOf(lerp(front, insert, .16f), front)), PocketFrontShape)
            .border(if (highlighted) 2.dp else 1.dp, if (highlighted) colors.primary else edge, PocketFrontShape))
        Row(Modifier.fillMaxWidth().heightIn(min = 94.dp).padding(start = 12.dp, end = 12.dp, top = 40.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp), tint = colors.onPrimaryContainer)
            Surface(Modifier.weight(1f), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
                shape = RoundedCornerShape(4.dp)) {
                Text(name, Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** A shallow dip at the opening separates the front pocket from its paper insert. */
private object PocketFrontShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val radius = with(density) { 8.dp.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        val lip = with(density) { 6.dp.toPx() }.coerceAtMost(size.height / 4f)
        return Outline.Generic(Path().apply {
            moveTo(0f, radius)
            quadraticTo(0f, 0f, radius, 0f)
            lineTo(size.width * .32f, 0f)
            quadraticTo(size.width / 2f, lip, size.width * .68f, 0f)
            lineTo(size.width - radius, 0f)
            quadraticTo(size.width, 0f, size.width, radius)
            lineTo(size.width, size.height - radius)
            quadraticTo(size.width, size.height, size.width - radius, size.height)
            lineTo(radius, size.height)
            quadraticTo(0f, size.height, 0f, size.height - radius)
            close()
        })
    }
}
