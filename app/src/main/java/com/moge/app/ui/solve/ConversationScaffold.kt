package com.moge.app.ui.solve

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.moge.app.ui.components.GridPaper
import com.moge.app.ui.components.PaperHeaderBackdrop
import com.moge.app.ui.components.headerInputShield
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.MogeTheme

/** The list continues beneath both overlays; padding keeps its first and last items reachable. */
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
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    var headerPixels by remember { mutableIntStateOf(0) }
    var footerPixels by remember { mutableIntStateOf(0) }
    var savingTitle by remember(editingTitle) { mutableStateOf(false) }
    val titleEditorVisible = editingTitle && onRenameTitle != null
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun dismissTitle() {
        if (savingTitle) return
        keyboard?.hide()
        focusManager.clearFocus()
        onTitleEditingChange(false)
    }
    GridPaper(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                content(PaddingValues(start = 16.dp, end = 16.dp,
                    top = with(density) { headerPixels.toDp() } + 20.dp,
                    bottom = with(density) { footerPixels.toDp() } + 20.dp))
                PaperHeaderBackdrop(Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .height(with(density) { headerPixels.toDp() } + 40.dp))
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().testTag("conversation-header")
                    .zIndex(if (titleEditorVisible) 2f else 0f)
                    .then(if (titleEditorVisible) Modifier else Modifier.headerInputShield())
                    .animateContentSize(tween(if (MogeTheme.motionEnabled) 160 else 0))
                    .onSizeChanged { headerPixels = it.height }
                    .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (titleEditorVisible && onBack != null) Spacer(Modifier.width(48.dp))
                    else if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    } else Spacer(Modifier.width(12.dp))
                    conversationMode?.takeUnless { editingTitle }?.let { mode ->
                        Icon(if (mode == ConversationMode.CHAT) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.School,
                            mode.label, Modifier.padding(end = 4.dp).size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Box(Modifier.weight(1f)) {
                        if (titleEditorVisible) {
                            ConversationTitleEditor(title, onRenameTitle, ::dismissTitle,
                                onSavingChange = { savingTitle = it })
                        } else {
                            ConversationTitle(title, newConversationMode, modeSelectable,
                                onEditTitle = { onTitleEditingChange(true) }.takeIf { newConversationMode == null && onRenameTitle != null },
                                onSelectMode = onSelectMode)
                        }
                    }
                    if (!editingTitle) actions()
                }
                if (onDismissEditing != null) {
                    // Keep the composer above the scrim; old bubbles and navigation
                    // behind it must not receive taps while an input is being edited.
                    Box(Modifier.matchParentSize().testTag("editing-background-scrim")
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClickLabel = "取消修改输入", onClick = onDismissEditing))
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("conversation-composer")
                    .onSizeChanged { footerPixels = it.height }) { footer() }
                if (titleEditorVisible) {
                    // Consume the cancelling tap over messages, composer and empty header space.
                    Box(Modifier.matchParentSize().zIndex(1f).testTag("title-editing-outside")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClickLabel = "取消修改主题", onClick = ::dismissTitle))
                }
            }
        }
    }
}
