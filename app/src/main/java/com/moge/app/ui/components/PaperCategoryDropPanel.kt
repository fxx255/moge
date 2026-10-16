package com.moge.app.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.ui.theme.MogeTheme

/** Expands inside the existing search sheet; only the floating dragged card is an overlay. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaperCategoryDropPanel(
    state: PaperDragState,
    categories: List<NotebookCategoryEntity>,
) {
    val motion = MogeTheme.motionEnabled
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(state.held) { if (state.held) scroll.scrollTo(0) }
    androidx.compose.animation.AnimatedVisibility(state.held,
        enter = expandVertically(tween(if (motion) 180 else 0), expandFrom = Alignment.Top) + fadeIn(tween(if (motion) 120 else 0)),
        exit = shrinkVertically(tween(if (motion) 160 else 0), shrinkTowards = Alignment.Top) + fadeOut(tween(if (motion) 100 else 0))) {
        Column {
            Text(if (state.categoriesExpanded) "松手放入分组" else "向上拖入分组，展开全部分组",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag("drop-category-prompt")
                    .onGloballyPositioned { state.categoryPromptBounds = it.boundsInRoot() }
                    .padding(horizontal = 9.dp, vertical = 12.dp))
            // Only the revealed portion of the animated sheet accepts a drop.
            BoxWithConstraints(Modifier.fillMaxWidth().clipToBounds()
                .onGloballyPositioned { state.categoryBounds = it.boundsInRoot() }) {
                val categoryWidth = if (maxWidth < 240.dp) maxWidth else (maxWidth - 8.dp) / 2
                // Measure after search, filters and prompt. Bulk actions are
                // hidden while dragging, so only the delete target needs space.
                val reserve = with(density) { state.rootBounds.height.toDp() } * PAPER_DELETE_HEIGHT_FRACTION
                val gridMaxHeight = (maxHeight - reserve).coerceAtLeast(0.dp)
                androidx.compose.animation.AnimatedVisibility(state.categoriesExpanded,
                    enter = expandVertically(tween(if (motion) 220 else 0), expandFrom = Alignment.Top) + fadeIn(tween(if (motion) 160 else 0)),
                    exit = shrinkVertically(tween(if (motion) 160 else 0), shrinkTowards = Alignment.Top) + fadeOut(tween(if (motion) 120 else 0))) {
                    FlowRow(Modifier.fillMaxWidth().heightIn(max = gridMaxHeight).verticalScroll(scroll)
                        .testTag("drop-category-grid").padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val choices = listOf(PaperDropTarget.Category(null, "未分类")) +
                            categories.map { PaperDropTarget.Category(it.id, it.name) }
                        choices.forEach { target ->
                            key(target.id) {
                                DisposableEffect(state, target) { onDispose { state.removeTarget(target) } }
                                PaperCategoryPocket(target.name, highlighted = state.hovered == target,
                                    modifier = Modifier.width(categoryWidth).testTag("drop-category-${target.id ?: "none"}")
                                        .onGloballyPositioned { state.targetBounds(target, it) })
                            }
                        }
                    }
                }
            }
        }
    }
}
