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
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.moge.app.ui.components.GridPaper
import com.moge.app.ui.components.StackedPaperHeader
import com.moge.app.ui.components.headerInputShield
import com.moge.app.domain.ConversationMode
import com.moge.app.ui.theme.MogeTheme

/** Preserve list geometry while clipping its drawing below the header; the composer floats above it. */
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
    var headerPixels by remember { mutableIntStateOf(0) }
    var footerPixels by remember { mutableIntStateOf(0) }
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
        GridPaper(Modifier.matchParentSize().then(pageMotion.readingModifier)) {}
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                val editorMaxHeight = (maxHeight - 84.dp).coerceAtLeast(96.dp)
                Box(Modifier.fillMaxSize().then(pageMotion.readingModifier).drawWithContent {
                    // Preserve the viewport and scroll offsets, but draw no messages
                    // in the header or upper gap. The theme background stays visible there.
                    clipRect(top = headerPixels.toFloat().coerceAtMost(size.height)) {
                        this@drawWithContent.drawContent()
                    }
                }) {
                    content(PaddingValues(start = 16.dp, end = 16.dp,
                        top = with(density) { headerPixels.toDp() } + 20.dp,
                        bottom = with(density) { footerPixels.toDp() } + 20.dp))
                }
                if (pageMotion.active) StackedPaperHeader(progress = { ribbon.progress.value },
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .zIndex(if (titleEditorVisible) 2f else 0f)
                        .then(if (titleEditorVisible) Modifier else Modifier.headerInputShield())
                        .onSizeChanged { headerPixels = it.height }) {
                    Column(Modifier.fillMaxWidth().testTag("conversation-header")
                        .semantics { conversationMode?.let { stateDescription = strings.text(R.string.ui_1_s_mode_3cfe2, strings.label(it)) } }
                        .animateContentSize(tween(if (motionEnabled) 200 else 0))) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (titleEditorVisible && onBack != null) Spacer(Modifier.width(48.dp))
                            else if (onBack != null) IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.text(R.string.ui_back_11d02))
                            } else Spacer(Modifier.width(12.dp))
                            (newConversationMode ?: conversationMode)?.let { mode ->
                                Icon(if (mode == ConversationMode.CHAT) Icons.AutoMirrored.Outlined.Chat else Icons.Outlined.School,
                                    strings.text(R.string.ui_1_s_mode_3cfe2, strings.label(mode)), Modifier.padding(end = 4.dp).size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                            }
                            Box(Modifier.weight(1f)) {
                                ConversationTitle(title, newConversationMode, modeSelectable,
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
                if (onDismissEditing != null) {
                    // Keep the composer above the scrim; old bubbles and navigation
                    // behind it must not receive taps while an input is being edited.
                    Box(Modifier.matchParentSize().testTag("editing-background-scrim")
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.48f))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClickLabel = strings.text(R.string.ui_cancel_editing_input_d041a), onClick = onDismissEditing))
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().testTag("conversation-composer")
                    .onSizeChanged { footerPixels = it.height }) {
                    if (pageMotion.active) footer()
                    else Spacer(Modifier.height(with(density) { footerPixels.toDp() }))
                }
                if (titleEditorVisible) {
                    // Consume the cancelling tap over messages, composer and empty header space.
                    Box(Modifier.matchParentSize().zIndex(1f).testTag("title-editing-outside")
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                            onClickLabel = strings.text(R.string.ui_cancel_editing_title_1684f), onClick = ::dismissTitle))
                }
            }
        }
    }
}
