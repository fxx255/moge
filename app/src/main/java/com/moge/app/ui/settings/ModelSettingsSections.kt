package com.moge.app.ui.settings

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.moge.app.data.credential.AiModelProfile

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelsSection(
    state: ModelsUiState,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onTest: () -> Unit,
) {
    val strings = rememberAppStrings()
    var pendingDelete by remember { mutableStateOf<AiModelProfile?>(null) }
    val selected = state.profiles.firstOrNull { it.id == state.activeId }
    SettingsSection(strings.text(R.string.ui_models_98fd0)) {
        Hint(strings.text(R.string.ui_use_any_openai_compatible_api_keys_are_encrypted_on_this_dev_f5c9f))
        ModelProfileDropdown(
            label = strings.text(R.string.ui_current_model_a0af8),
            choices = state.profiles.map { ModelChoice(it.id, it.name, profileSummary(it, strings)) },
            selectedId = state.activeId,
            placeholder = if (state.profiles.isEmpty()) strings.text(R.string.ui_no_models_yet_add_one_to_get_started_c1f2d) else strings.text(R.string.ui_choose_a_model_7330b),
            enabled = !state.testing,
            tag = "primary-model-selector",
            onSelect = { id -> id?.let(onSelect) },
        )
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onAdd, enabled = !state.testing,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(strings.text(R.string.ui_add_model_532a6))
            }
            if (selected != null) {
                OutlinedButton(onClick = onTest, enabled = !state.testing,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(if (state.testing) strings.text(R.string.ui_testing_49562) else strings.text(R.string.ui_test_connection_10b7d))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onEdit(selected.id) }, enabled = !state.testing) {
                        Icon(Icons.Outlined.Edit, strings.text(R.string.ui_edit_1_s_60333, selected.name))
                    }
                    IconButton(onClick = { pendingDelete = selected }, enabled = !state.testing) {
                        Icon(Icons.Outlined.Delete, strings.text(R.string.ui_delete_1_s_ef70c, selected.name), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        state.message?.let { StatusText(it) }
    }
    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(strings.text(R.string.ui_delete_model_configuration_8c008)) },
            text = { Text(strings.text(R.string.ui_delete_1_s_and_its_api_key_stored_on_this_device_61b69, profile.name)) },
            confirmButton = { TextButton(onClick = { onDelete(profile.id); pendingDelete = null }) {
                Text(strings.text(R.string.ui_delete_3755f), color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(strings.text(R.string.ui_cancel_4d0b4)) } },
        )
    }
}

@Composable
internal fun VisionSection(state: ModelsUiState, onSelect: (String?) -> Unit) {
    val strings = rememberAppStrings()
    val candidates = state.profiles.filter { it.visionEnabled }
    SettingsSection(strings.text(R.string.ui_question_recognition_model_a3be2)) {
        Hint(strings.text(R.string.ui_if_the_main_model_cannot_read_images_use_an_image_capable_mo_1ad14))
        ModelProfileDropdown(
            label = strings.text(R.string.ui_photo_recognition_b818d),
            choices = listOf(ModelChoice(null, strings.text(R.string.ui_none_1aad3), strings.text(R.string.ui_use_the_current_model_s_vision_ad013))) +
                candidates.map { ModelChoice(it.id, it.name, it.model) },
            selectedId = state.visionProfileId,
            placeholder = strings.text(R.string.ui_choose_a_recognition_model_7d45a),
            enabled = !state.testing,
            tag = "vision-model-selector",
            onSelect = onSelect,
        )
        StatusText(photoRouteHint(state.profiles, state.activeId, state.visionProfileId, strings))
    }
}
