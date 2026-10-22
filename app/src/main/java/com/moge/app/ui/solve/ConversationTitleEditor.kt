package com.moge.app.ui.solve

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.moge.app.domain.ConversationName
import com.moge.app.ui.components.excludePageSwipe
import com.moge.app.ui.components.headerInputShield
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.launch

/** An inline sticky note. Saving and outside dismissal share the scaffold's busy state. */
@Composable
internal fun ConversationTitleEditor(
    title: String,
    onSave: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
    onSavingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(title, TextRange(0, title.length)))
    }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val validation = remember(value.text) { runCatching { ConversationName.normalize(value.text) } }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val paper = MogeTheme.paper
    val colors = MaterialTheme.colorScheme
    fun dismiss() {
        if (saving) return
        onDismiss()
    }
    fun save() {
        val name = validation.getOrNull() ?: return
        if (saving) return
        saving = true
        onSavingChange(true)
        scope.launch {
            try {
                if (onSave(name)) {
                    onSavingChange(false)
                    onDismiss()
                } else failed = true
            } finally {
                saving = false
                onSavingChange(false)
            }
        }
    }
    BackHandler { dismiss() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        focus.requestFocus()
        keyboard?.show()
    }
    Surface(modifier.excludePageSwipe().headerInputShield().testTag("conversation-title-sheet"),
        shape = CutCornerShape(topEnd = 14.dp), color = paper.stickyNote,
        contentColor = paper.onStickyNote, shadowElevation = 6.dp) {
        Column(Modifier
            .drawBehind {
                drawRect(Brush.verticalGradient(listOf(paper.onStickyNote.copy(alpha = 0.045f),
                    paper.onStickyNote.copy(alpha = 0f))))
                drawRect(paper.onStickyNote.copy(alpha = 0.035f), size = Size(size.width, 16.dp.toPx()))
                val fold = 14.dp.toPx()
                drawPath(Path().apply {
                    moveTo(size.width - fold, 0f)
                    lineTo(size.width - fold, fold)
                    lineTo(size.width, fold)
                    close()
                }, paper.onStickyNote.copy(alpha = 0.12f))
            }.padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 4.dp)) {
            BasicTextField(value, onValueChange = { if (!saving) { value = it; failed = false } },
                readOnly = saving,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = paper.onStickyNote),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                    .padding(end = 8.dp).heightIn(min = 48.dp)
                    .focusRequester(focus).testTag("conversation-title-editor")
                    .semantics { contentDescription = "对话主题，1至${ConversationName.MAX_CODE_POINTS}个字" })
            val error = validation.exceptionOrNull()?.message ?: if (failed) "主题未保存，请重试" else null
            if (error != null) Text(error, style = MaterialTheme.typography.labelMedium, color = colors.error)
            IconButton(onClick = ::save, enabled = !saving && validation.isSuccess,
                modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.Check, "保存主题", tint = paper.onStickyNote.copy(
                    alpha = if (!saving && validation.isSuccess) 1f else 0.38f))
            }
        }
    }
}
