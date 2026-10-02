package com.moge.app.ui.solve

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme

/** A paper crease opens the locally stored explanation; only the content reveal is animated. */
@Composable
internal fun PaperExplanationFold(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val paper = MogeTheme.paper
    val motion = MogeTheme.motionEnabled
    val progress by animateFloatAsState(
        if (expanded) 1f else 0f,
        animationSpec = if (motion) tween(420, easing = FastOutSlowInEasing) else snap(),
        label = "paper-fold",
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .drawBehind {
                    val creaseEnd = size.width - 30.dp.toPx()
                    drawLine(paper.cardStroke.copy(alpha = 0.35f), Offset(0f, 1.dp.toPx()),
                        Offset(creaseEnd, 1.dp.toPx()), 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
                    val fold = (24f * (1f - progress) + 5f).dp.toPx()
                    val corner = Path().apply {
                        moveTo(size.width - fold, 0f)
                        lineTo(size.width, fold)
                        lineTo(size.width, 0f)
                        close()
                    }
                    drawPath(corner, paper.tape)
                    drawLine(paper.cardStroke.copy(alpha = 0.4f), Offset(size.width - fold, 0f),
                        Offset(size.width, fold), 1.dp.toPx())
                }
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起解答" else "展开解答", onClick = onToggle)
                .semantics { stateDescription = if (expanded) "解答已展开" else "解答已折叠" }
                .padding(start = 8.dp, end = 32.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "收起解答" else "展开解答", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.ExpandMore, null, Modifier.rotate(180f * progress), tint = MaterialTheme.colorScheme.primary)
        }
        if (motion) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(420, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) + fadeIn(tween(260, 70)),
                exit = shrinkVertically(tween(340, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(160)),
            ) { Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { content() } }
        } else if (expanded) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { content() }
        }
    }
}
