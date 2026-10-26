package com.moge.app.ui.notebook

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.moge.app.ui.theme.MogeTheme

@Composable
internal fun CategoryCard(
    name: String,
    enabled: Boolean,
    onRename: () -> Unit,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier,
    floating: Boolean = false,
    showHandle: Boolean = true,
    selected: Boolean = false,
    editingValue: TextFieldValue? = null,
    creating: Boolean = false,
    onEditChange: (TextFieldValue) -> Unit = {},
    onSaveEdit: () -> Unit = {},
    onCancelEdit: () -> Unit = {},
) {
    val strings = rememberAppStrings()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val editorFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editingValue != null) {
        if (editingValue != null) {
            editorFocus.requestFocus()
            withFrameNanos { }
            keyboard?.show()
        }
    }
    CategoryPaperSurface(modifier.semantics { if (selected) this.selected = true }, floating = floating,
        selected = selected.takeIf { it }) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (editingValue != null) {
                val cleaned = editingValue.text.trim()
                val valid = cleaned.isNotEmpty() && cleaned.codePointCount(0, cleaned.length) <= 80
                OutlinedTextField(editingValue, onEditChange, enabled = enabled, singleLine = true,
                    isError = !valid, placeholder = { Text(strings.text(R.string.ui_category_name_3fc30)) },
                    textStyle = MaterialTheme.typography.titleMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onSaveEdit() }),
                    modifier = Modifier.weight(1f).padding(end = 8.dp).focusRequester(editorFocus)
                        .testTag("category-name-editor").semantics { contentDescription = strings.text(R.string.ui_category_name_1_80_characters_fbe4a) })
                TextButton(onClick = onSaveEdit, enabled = enabled && valid,
                    modifier = Modifier.semantics { contentDescription = strings.text(R.string.ui_save_category_name_8205e) }) { Text(strings.text(R.string.ui_save_fadf2)) }
                IconButton(onClick = onCancelEdit) { Icon(Icons.Outlined.Close, if (creating) strings.text(R.string.ui_cancel_creation_e41a0) else strings.text(R.string.ui_cancel_rename_ad64f)) }
            } else {
                Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
                IconButton(onClick = onRename, enabled = enabled) { Icon(Icons.Outlined.Edit, strings.text(R.string.ui_rename_1_s_829f6, name)) }
                if (showHandle) Box(handleModifier.size(48.dp).semantics {
                    contentDescription = strings.text(R.string.ui_hold_to_drag_1_s_637c3, name)
                    role = Role.Button
                    stateDescription = strings.text(R.string.ui_hold_and_drag_to_reorder_or_drag_down_to_delete_7d1ed)
                    if (!enabled) disabled()
                }, contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size(22.dp, 18.dp)) {
                        for (line in 0..2) {
                            val y = size.height * (0.2f + line * 0.3f)
                            drawLine(ink, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                        }
                    }
                }
            }
        }
    }
}

/** Both category workspaces use the same punched paper, rules and lift elevation. */
@Composable
internal fun CategoryPaperSurface(
    modifier: Modifier = Modifier,
    floating: Boolean = false,
    onClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val paper = MaterialTheme.colorScheme.background
    val rule = MogeTheme.paper.grid
    val tap = when {
        onClick == null -> Modifier
        selected != null -> Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
        else -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    }
    Surface(modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 12.dp, bottomEnd = 12.dp),
        color = if (selected == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (selected == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = if (floating) 12.dp else 2.dp) {
        Box(Modifier.fillMaxWidth().heightIn(min = 76.dp).then(tap).drawBehind {
            for (fraction in listOf(0.28f, 0.72f)) {
                drawCircle(paper, 2.5.dp.toPx(), Offset(8.dp.toPx(), size.height * fraction))
            }
            drawLine(rule, Offset(20.dp.toPx(), size.height - 10.dp.toPx()),
                Offset(size.width - 12.dp.toPx(), size.height - 10.dp.toPx()), 0.5.dp.toPx())
        }, contentAlignment = Alignment.CenterStart, content = content)
    }
}
