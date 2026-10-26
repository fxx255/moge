package com.moge.app.ui.solve

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.moge.app.R
import com.moge.app.ui.components.PaperModalSheet
import com.moge.app.ui.components.marginLine
import com.moge.app.ui.export.ExportPreviewImage
import com.moge.app.ui.localization.rememberAppStrings
import com.moge.app.ui.theme.MogeTheme

/** Choose the question snapshot before either collecting or sharing its answer. */
@Composable
internal fun QuestionSelectionDialog(current: SolveItem.Question, alternatives: List<SolveItem.Question>,
    collect: Boolean, onDismiss: () -> Unit, onChoose: (SolveItem.Question) -> Unit) {
    val strings = rememberAppStrings()
    var selectedId by rememberSaveable(current.id) { mutableStateOf(current.id) }
    val selected = alternatives.firstOrNull { it.id == selectedId }
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PaperModalSheet(strings.text(if (collect) R.string.ui_choose_question_to_save_d5dba
            else R.string.ui_choose_question_to_share_84103), strings.text(R.string.ui_cancel_4d0b4),
            onDismiss, Modifier.testTag("question-selection-dialog")) {
            Text(strings.text(R.string.ui_this_follow_up_has_no_image_you_can_include_earlier_question_1b9ac),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                item(key = current.id) {
                    QuestionChoiceCard(strings.text(R.string.ui_this_question_only_f03e2).trimEnd('：', ':', ' '), current,
                        selectedId == current.id, { selectedId = current.id })
                }
                items(alternatives, key = { it.id }) { original ->
                    QuestionChoiceCard(strings.text(R.string.ui_original_question_and_follow_up_89027).trimEnd('：', ':', ' '), original,
                        selectedId == original.id, { selectedId = original.id })
                }
            }
            Button(onClick = { onChoose(selected?.let { withFollowUp(it, current) } ?: current) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("question-selection-confirm")) {
                Text(strings.text(R.string.ui_confirm_b56d9))
            }
        }
    }
}

@Composable
private fun QuestionChoiceCard(label: String, question: SolveItem.Question, selected: Boolean, onClick: () -> Unit) {
    val strings = rememberAppStrings()
    val colors = MaterialTheme.colorScheme
    Surface(selected = selected, onClick = onClick, color = colors.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, if (selected) colors.primary else MogeTheme.paper.cardStroke),
        modifier = Modifier.fillMaxWidth().testTag("question-choice-${question.id}")
            .semantics { role = Role.RadioButton }) {
        Column(Modifier.fillMaxWidth()
            .marginLine(MogeTheme.paper.marginLine, inset = 16.dp)
            .padding(start = 28.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected, onClick = null)
                Spacer(Modifier.width(10.dp))
                Text(label, style = MaterialTheme.typography.titleMedium,
                    color = if (selected) colors.primary else colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            }
            Text(question.text.ifBlank { strings.text(R.string.ui_photo_question_770f1) },
                style = MaterialTheme.typography.bodyLarge, maxLines = if (selected) 6 else 3, overflow = TextOverflow.Ellipsis)
            if (selected && question.photoPaths.isNotEmpty()) {
                Text(strings.text(R.string.ui_question_photo_1_s_2_s_9fff7, 1, question.photoPaths.size),
                    style = MaterialTheme.typography.labelLarge, color = colors.primary)
                Box(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                    ExportPreviewImage(question.photoPaths.first(), strings.text(R.string.ui_question_photo_1_s_to_confirm_df025, 1))
                }
            }
        }
    }
}
