package com.moge.app.ui.solve

import com.moge.app.ui.localization.label
import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.moge.app.ui.components.GridPaper
import com.moge.app.ui.components.aboveMovingPages
import com.moge.app.ui.components.PaperStripHeader
import com.moge.app.ui.components.PaperHeaderMinHeight
import com.moge.app.ui.components.PaperHeaderIconButton
import com.moge.app.ui.components.PaperHeaderLayout
import com.moge.app.ui.components.PaperHeaderTopGap
import com.moge.app.ui.components.PaperHeaderContentGap
import com.moge.app.ui.components.headerInputShield
import com.moge.app.ui.components.LocalPaperNavigationScope
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.MogeTheme

/** Preserve reading geometry; messages pass above the underlay and below the fixed front strip. */
@Composable
internal fun ConversationScaffold(
    title: String, onBack: (() -> Unit)?, actions: @Composable () -> Unit,
    footer: @Composable () -> Unit, onDismissEditing: (() -> Unit)? = null,
    conversationMode: ConversationMode? = null,
    newConversationMode: ConversationMode? = null,
    modeSelectable: Boolean = true,
    onSelectMode: (ConversationMode) -> Unit = {},
    editingTitle: Boolean = false,
    onTitleEditingChange: (Boolean) -> Unit = {},
    onRenameTitle: (suspend (String) -> Boolean)? = null,
    pageMotion: ConversationPageMotion = ConversationPageMotion(),
    ribbonState: ConversationRibbonState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    val strings = rememberAppStrings()
    val density = LocalDensity.current
    var footerPixels by remember { mutableIntStateOf(0) }
    val view = LocalView.current
    var footerExitDistance by remember { mutableFloatStateOf(view.height.toFloat()) }
    val navigationPaper = LocalPaperNavigationScope.current
    val contentFooterPixels = if (pageMotion.composerActive && footerPixels > 0) footerPixels
        else navigationPaper?.composerHeight ?: footerPixels
    var savingTitle by remember(editingTitle) { mutableStateOf(false) }
    val titleEditorVisible = editingTitle && onRenameTitle != null
    val existing = newConversationMode == null
    val ribbon = ribbonState ?: remember { ConversationRibbonState(existing) }
    val motionEnabled = MogeTheme.motionEnabled
    LaunchedEffect(ribbon, existing, pageMotion.active, motionEnabled) {
        if (pageMotion.active) ribbon.show(existing, motionEnabled)
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun dismissTitle() {
        if (savingTitle) return
        keyboard?.hide()
        focusManager.clearFocus()
        onTitleEditingChange(false)
    }
    Box(Modifier.fillMaxSize()) {
        GridPaper(Modifier.matchParentSize()) {}
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val editorMaxHeight = (maxHeight - 84.dp).coerceAtLeast(96.dp)
                PaperHeaderLayout(Modifier.fillMaxSize(), header = {
                    PaperStripHeader(progress = { ribbon.progress.value },
                        targetProgress = if (existing) 1f else 0f,
                        overlayZIndex = if (titleEditorVisible) 4f else 1f,
                        active = pageMotion.active,
                        modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                            .zIndex(if (titleEditorVisible) 2f else 0f)
                            .then(if (titleEditorVisible) Modifier else Modifier.headerInputShield())) {
                        Column(Modifier.fillMaxWidth().testTag("conversation-header")
                            .semantics { conversationMode?.let { stateDescription = strings.text(R.string.ui_1_s_mode_3cfe2, strings.label(it)) } }
                            .animateContentSize(tween(if (motionEnabled) 200 else 0))) {
                            Row(Modifier.fillMaxWidth().heightIn(min = PaperHeaderMinHeight)
                                .padding(start = 4.dp, end = 8.dp, top = 3.dp, bottom = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (titleEditorVisible && onBack != null) Spacer(Modifier.width(48.dp))
                                else if (onBack != null) PaperHeaderIconButton(onClick = onBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.text(R.string.ui_back_11d02))
                                } else Spacer(Modifier.width(12.dp))
                                Box(Modifier.weight(1f)) {
                                    ConversationTitle(title, newConversationMode, modeSelectable,
                                        conversationMode = conversationMode,
                                        onEditTitle = { onTitleEditingChange(true) }.takeIf {
                                            !titleEditorVisible && newConversationMode == null && onRenameTitle != null
                                        },
                                        onSelectMode = onSelectMode)
                                }
                                if (!editingTitle) actions()
                            }
                            if (titleEditorVisible) ConversationTitleEditor(title, onRenameTitle, ::dismissTitle,
                                onSavingChange = { savingTitle = it },
                                modifier = Modifier.fillMaxWidth().heightIn(max = editorMaxHeight))
                        }
                    }
                }) { headerHeight ->
                    Box(Modifier.fillMaxSize().then(pageMotion.readingModifier).drawWithContent {
                        // The front strip covers messages. Clip only the upper gap so
                        // photographs can cover the exposed edge of the paper underneath.
                        val top = if (titleEditorVisible) headerHeight.toPx() else PaperHeaderTopGap.toPx()
                        clipRect(top = top.coerceAtMost(size.height)) { this@drawWithContent.drawContent() }
                    }) {
                        content(PaddingValues(start = 16.dp, end = 16.dp,
                            top = headerHeight + PaperHeaderContentGap,
                            bottom = with(density) { contentFooterPixels.toDp() } + 20.dp))
                    }
                    if (onDismissEditing != null) {
                        // Keep the composer above the scrim; old bubbles and navigation
                        // behind it must not receive taps while an input is being edited.
                        Box(Modifier.matchParentSize().aboveMovingPages(true, 2f).testTag("editing-background-scrim")
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f))
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClickLabel = strings.text(R.string.ui_cancel_editing_input_d041a), onClick = onDismissEditing))
                    }
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .aboveMovingPages(pageMotion.composerActive, 3f)
                        .onGloballyPositioned {
                            // Include the safe-area/IME gap and shadow, so even a tall
                            // attachment tray clears the window before its page leaves.
                            footerExitDistance = it.findRootCoordinates().size.height - it.positionInRoot().y +
                                with(density) { 16.dp.toPx() }
                        }) {
                        // Measure and record from the stationary parent; transforming
                        // that parent would feed its displaced position back into the distance.
                        Column(Modifier.fillMaxWidth().graphicsLayer {
                            translationY = footerExitDistance * pageMotion.composerExitFraction().coerceIn(0f, 1f)
                        }.testTag("conversation-composer").onSizeChanged {
                            footerPixels = it.height
                            if (pageMotion.composerActive) navigationPaper?.composerHeight = it.height
                        }) {
                            if (pageMotion.composerActive) footer()
                            else Spacer(Modifier.height(with(density) { footerPixels.toDp() }))
                        }
                    }
                    if (titleEditorVisible) {
                        // Consume the cancelling tap over messages, composer and empty header space.
                        Box(Modifier.matchParentSize().aboveMovingPages(true, 3.5f).zIndex(1f).testTag("title-editing-outside")
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClickLabel = strings.text(R.string.ui_cancel_editing_title_1684f), onClick = ::dismissTitle))
                    }
                }
            }
        }
    }
}
