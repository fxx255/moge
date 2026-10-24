package com.moge.app.ui.components

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
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
import kotlin.math.sign

/** Expands inside the existing search sheet; only the floating dragged card is an overlay. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PaperCategoryDropPanel(
    state: PaperDragState,
    categories: List<NotebookCategoryEntity>,
) {
    val strings = rememberAppStrings()
    val motion = MogeTheme.motionEnabled
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    CategoryDropAutoScroll(state, scroll)
    LaunchedEffect(state.held) { if (state.held) scroll.scrollTo(0) }
    androidx.compose.animation.AnimatedVisibility(state.held,
        enter = expandVertically(tween(if (motion) 180 else 0), expandFrom = Alignment.Top) + fadeIn(tween(if (motion) 120 else 0)),
        exit = shrinkVertically(tween(if (motion) 160 else 0), shrinkTowards = Alignment.Top) + fadeOut(tween(if (motion) 100 else 0))) {
        Column {
            Text(if (state.categoriesExpanded) strings.text(R.string.ui_release_to_move_to_category_158e0) else strings.text(R.string.ui_drag_up_to_expand_categories_455c9),
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
                        val choices = listOf(PaperDropTarget.Category(null, strings.text(R.string.ui_uncategorized_b28f1))) +
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

/** A held card owns the pointer, so the scroll container cannot receive a second drag gesture. */
@Composable
private fun CategoryDropAutoScroll(state: PaperDragState, scroll: ScrollState) {
    val density = LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    val speed = with(density) { 520.dp.toPx() }
    val active = state.held && state.categoriesExpanded
    val direction = if (active) state.visual?.pointer?.let {
        dragEdgeScrollDirection(it, state.categoryBounds, state.deleteBounds, edge).sign
    } ?: 0f else 0f
    // Restart only when entering/leaving an edge, not on each pointer update.
    LaunchedEffect(active, direction, scroll, speed) {
        if (!active || direction == 0f) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (state.held && state.categoriesExpanded) {
            val frame = withFrameNanos { it }
            val elapsed = ((frame - previous) / 1_000_000_000f).coerceIn(0f, 0.05f)
            previous = frame
            val pointer = state.visual?.pointer ?: break
            val amount = dragEdgeScrollDirection(pointer, state.categoryBounds, state.deleteBounds, edge)
            if (amount == 0f || scroll.scrollBy(amount * speed * elapsed) == 0f) break
            state.refreshTargets()
        }
    }
}
