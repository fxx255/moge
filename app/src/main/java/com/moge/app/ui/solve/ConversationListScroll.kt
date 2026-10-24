package com.moge.app.ui.solve

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlin.math.abs

internal class ConversationListScroll(
    val state: LazyListState,
    val beforeVersionSwitch: (String) -> Unit,
)

/** Prefer an on-screen message edge over a large offset into the preceding answer. */
private fun LazyListState.readingViewport(): ConversationViewport? {
    val visible = layoutInfo.visibleItemsInfo
    val readingEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    val anchor = visible.firstOrNull { it.offset >= 0 && it.offset < readingEnd }
        ?: visible.firstOrNull() ?: return null
    return ConversationViewport(anchor.key as? String ?: return null, anchor.index, -anchor.offset)
}

/** Frame-by-frame bookkeeping must not invalidate the message composition. */
private class ViewportRecord(var reading: ConversationViewport?) {
    var persisted: Pair<String, ConversationViewport>? = null
}

@Composable
internal fun rememberConversationListScroll(
    conversationId: String?,
    items: List<SolveItem>,
    initialViewport: ConversationViewport?,
    contentPadding: PaddingValues,
    active: Boolean,
    saveViewport: (ConversationViewport) -> Unit,
    readBranchViewport: (String) -> ConversationViewport?,
    saveBranchViewport: (String, ConversationViewport) -> Unit,
): ConversationListScroll {
    val ids = items.map { it.id }
    // Start at the reading anchor, avoiding a throwaway layout/render of the first long answer.
    // Navigation owns persistence; a second LazyListState Saver must not restore a competing offset.
    val list = remember(conversationId) {
        LazyListState(
            firstVisibleItemIndex = initialViewport?.let {
                if (ids.isEmpty()) it.index else it.indexIn(ids)
            } ?: ids.lastIndex.coerceAtLeast(0),
            firstVisibleItemScrollOffset = initialViewport?.offset ?: 0,
        )
    }
    var restored by remember(conversationId) { mutableStateOf(false) }
    var pin by remember(conversationId) { mutableStateOf(initialViewport) }
    var questionIds by remember(conversationId) { mutableStateOf<List<String>?>(null) }
    var pendingVersion by remember(conversationId) { mutableStateOf<String?>(null) }
    var fallback by remember(conversationId) { mutableStateOf<ConversationViewport?>(null) }
    val record = remember(conversationId) { ViewportRecord(initialViewport) }
    val save by rememberUpdatedState(saveViewport)
    val saveBranch by rememberUpdatedState(saveBranchViewport)
    val branch = items.filterIsInstance<SolveItem.Question>().lastOrNull()?.id.orEmpty()
    val currentBranch by rememberUpdatedState(branch)
    val currentActive by rememberUpdatedState(active)
    val currentIds by rememberUpdatedState(ids)

    fun persist() {
        if (!restored || pendingVersion != null) return
        record.reading?.let {
            val value = currentBranch to it
            if (record.persisted != value) {
                save(it)
                saveBranch(currentBranch, it)
                record.persisted = value
            }
        }
    }
    suspend fun restore(anchor: ConversationViewport?, fallbackIndex: Int) {
        list.scrollToItem(anchor?.indexIn(ids) ?: fallbackIndex, anchor?.offset ?: 0)
    }
    LaunchedEffect(conversationId, ids, contentPadding, active) {
        if (ids.isEmpty() || !active) return@LaunchedEffect
        val questions = items.filterIsInstance<SolveItem.Question>().map { it.id }
        if (!restored) {
            // Floating controls must report their insets before a short list can be clamped.
            withFrameNanos { }
            restore(initialViewport, ids.lastIndex)
            restored = true
        } else if (pendingVersion != null && pendingVersion in ids) {
            pin = readBranchViewport(branch) ?: fallback?.let {
                if (it.messageId in ids) it else it.copy(messageId = requireNotNull(pendingVersion), index = ids.indexOf(pendingVersion))
            }
            restore(pin, ids.indexOf(pendingVersion))
            pendingVersion = null
        } else if (questionIds != null && questions.any { it !in questionIds.orEmpty() }) {
            pin = null
            list.animateScrollToItem(ids.lastIndex)
        } else if (pin != null) {
            restore(pin, ids.lastIndex)
        }
        questionIds = questions
    }
    LaunchedEffect(list, conversationId, restored, active) {
        if (!restored || !active) return@LaunchedEffect
        var previousSizes: List<Pair<Any, Int>>? = null
        snapshotFlow {
            Triple(list.layoutInfo.visibleItemsInfo.map { Triple(it.key, it.offset, it.size) },
                list.isScrollInProgress, contentPadding)
        }.collect { (visible, scrolling, _) ->
            if (!currentActive || pendingVersion != null || currentIds.isEmpty()) return@collect
            if (scrolling) pin = null
            val sizes = visible.map { it.first to it.third }
            val anchor = pin
            // Native formulas/images can finish measuring after the initial placement. Keep the
            // restored edge pinned through these reflows, until the reader starts scrolling.
            if (!scrolling && anchor != null && sizes != previousSizes) {
                val edge = visible.firstOrNull { it.first == anchor.messageId }?.second
                if (edge == null || abs(edge + anchor.offset) > 1) {
                    // A native drawable can publish its new size from inside Android's layout.
                    // Schedule the next measurement instead of recursively forcing a layout here.
                    list.requestScrollToItem(anchor.indexIn(currentIds), anchor.offset)
                }
            }
            previousSizes = sizes
            record.reading = pin ?: list.readingViewport() ?: record.reading
            // Keep frame-by-frame positions in memory; Bundle copies happen only at rest/exit.
            if (!scrolling) persist()
        }
    }
    LaunchedEffect(active) { if (!active) persist() }
    DisposableEffect(list, conversationId) { onDispose { persist() } }
    return ConversationListScroll(list) { target ->
        pin = null
        record.reading = list.readingViewport() ?: record.reading
        persist()
        fallback = record.reading
        pendingVersion = target
    }
}
