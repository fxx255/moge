package com.moge.app.ui.components

import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.moge.app.data.db.NotebookCategoryEntity
import com.moge.app.ui.theme.MogeTheme

/** The expanded drop targets use the same raised paper and taped tabs as the collection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaperCategoryDropPanel(
    state: PaperDragState,
    categories: List<NotebookCategoryEntity>,
    scroll: ScrollState,
    categoryWidth: Dp,
    gridMaxHeight: Dp,
) {
    val motion = MogeTheme.motionEnabled
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(17.dp), shadowElevation = 3.dp,
    ) {
        Column {
            Text(if (state.categoriesExpanded) "松手放入分组" else "向上拖入分组，展开全部分组",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().testTag("drop-category-prompt")
                    .onGloballyPositioned { state.categoryPromptBounds = it.boundsInRoot() }
                    .padding(horizontal = 16.dp, vertical = 12.dp))
            // Only the revealed portion of the animated sheet accepts a drop.
            Box(Modifier.fillMaxWidth().clipToBounds()
                .onGloballyPositioned { state.categoryBounds = it.boundsInRoot() }) {
                androidx.compose.animation.AnimatedVisibility(state.categoriesExpanded,
                    enter = expandVertically(tween(if (motion) 220 else 0), expandFrom = Alignment.Top) + fadeIn(tween(if (motion) 160 else 0)),
                    exit = shrinkVertically(tween(if (motion) 160 else 0), shrinkTowards = Alignment.Top) + fadeOut(tween(if (motion) 120 else 0))) {
                    FlowRow(Modifier.fillMaxWidth().heightIn(max = gridMaxHeight).verticalScroll(scroll)
                        .testTag("drop-category-grid").padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val choices = listOf(PaperDropTarget.Category(null, "未分类")) +
                            categories.map { PaperDropTarget.Category(it.id, it.name) }
                        choices.forEach { target ->
                            key(target.id) {
                                DisposableEffect(state, target) { onDispose { state.removeTarget(target) } }
                                val highlighted = state.hovered == target
                                TapedPaperCard(Modifier.width(categoryWidth).testTag("drop-category-${target.id ?: "none"}")
                                    .onGloballyPositioned { state.targetBounds(target, it) }) { cardModifier ->
                                    Surface(modifier = cardModifier.heightIn(min = 56.dp),
                                        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer else MogeTheme.paper.scratch,
                                        contentColor = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                                        border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                                        shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)) {
                                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                                            Text(target.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
