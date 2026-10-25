package com.moge.app.ui.solve

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
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
import com.moge.app.ui.components.PaperHeaderIconButton
import com.moge.app.ui.components.headerInputShield
import com.moge.app.ui.theme.MogeTheme
import kotlinx.coroutines.launch

/** The header pulls down into a full-width writing strip; saving shares the scaffold's busy state. */
@Composable
internal fun ConversationTitleEditor(
    title: String,
    onSave: suspend (String) -> Boolean,
    onDismiss: () -> Unit,
    onSavingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = rememberAppStrings()
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
        color = paper.headerPaper, contentColor = colors.onBackground) {
        Column(Modifier
            .drawBehind {
                val crease = colors.outlineVariant.copy(alpha = 0.6f)
                drawLine(crease, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx())
                drawRect(Brush.verticalGradient(listOf(colors.onBackground.copy(alpha = 0.035f),
                    colors.onBackground.copy(alpha = 0f)), endY = 10.dp.toPx()))
            }.padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 4.dp)) {
            BasicTextField(value, onValueChange = { if (!saving) { value = it; failed = false } },
                readOnly = saving,
                textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.weight(1f, fill = false).fillMaxWidth()
                    .padding(end = 8.dp).heightIn(min = 48.dp)
                    .focusRequester(focus).testTag("conversation-title-editor")
                    .semantics { contentDescription = strings.text(R.string.ui_conversation_title_1_1_s_characters_a8bc3, ConversationName.MAX_CODE_POINTS) })
            val error = validation.exceptionOrNull()?.message ?: if (failed) strings.text(R.string.ui_title_not_saved_try_again_2317c) else null
            if (error != null) Text(error, style = MaterialTheme.typography.labelMedium, color = colors.error)
            PaperHeaderIconButton(onClick = ::save, enabled = !saving && validation.isSuccess,
                modifier = Modifier.align(Alignment.End)) {
                Icon(Icons.Outlined.Check, strings.text(R.string.ui_save_title_d582c), tint = colors.onBackground.copy(
                    alpha = if (!saving && validation.isSuccess) 1f else 0.38f))
            }
        }
    }
}
