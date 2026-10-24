package com.moge.app.ui.settings

import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.data.credential.AiModelProfile
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiApiProtocol
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.ui.components.PaperScaffold

/**
 * 模型配置编辑页（整页，不用对话框：字段多，小屏上对话框滚不开）。
 * [profile] 为 null 表示新建。密钥不回显，留空即沿用已保存的密钥。
 */
@Composable
fun ModelEditorScreen(
    profile: AiModelProfile?,
    vm: SettingsViewModel,
    onClose: () -> Unit,
) {
    val strings = rememberAppStrings()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val key = profile?.id
    var name by rememberSaveable(key) { mutableStateOf(profile?.name.orEmpty()) }
    var baseUrl by rememberSaveable(key) { mutableStateOf(profile?.baseUrl.orEmpty()) }
    var model by rememberSaveable(key) { mutableStateOf(profile?.model.orEmpty()) }
    var apiKey by rememberSaveable(key) { mutableStateOf("") }
    var showKey by rememberSaveable(key) { mutableStateOf(false) }
    var vision by rememberSaveable(key) { mutableStateOf(profile?.visionEnabled ?: false) }
    var protocol by rememberSaveable(key) { mutableStateOf(profile?.searchProtocol ?: AiSearchProtocol.RESPONSES) }
    var apiProtocol by rememberSaveable(key) { mutableStateOf(profile?.apiProtocol ?: AiApiProtocol.fromLegacy(profile?.searchProtocol ?: AiSearchProtocol.CHAT_COMPLETIONS)) }
    var searchEnabled by rememberSaveable(key) { mutableStateOf(profile?.searchEnabled ?: (profile?.searchProtocol != AiSearchProtocol.OFF)) }
    var effort by rememberSaveable(key) { mutableStateOf(profile?.reasoningEffort ?: AiReasoningEffort.LOW) }
    val hasStoredKey = profile?.hasApiKey == true
    LaunchedEffect(key) {
        if (hasStoredKey) {
            vm.fetchModels(profile.baseUrl, "", profile.id, profile.searchProtocol)
        }
    }

    fun draft() = ModelProfileDraft(name, baseUrl, model, apiKey, vision, when (apiProtocol) { AiApiProtocol.CHAT_COMPLETIONS -> AiSearchProtocol.CHAT_COMPLETIONS; AiApiProtocol.RESPONSES -> AiSearchProtocol.RESPONSES }, effort, apiProtocol, searchEnabled)

    PaperScaffold(
        title = if (profile == null) strings.text(R.string.ui_add_model_532a6) else strings.text(R.string.ui_edit_model_29e31),
        onBack = onClose,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(strings.text(R.string.ui_api_8b48e)) {
                if (profile == null) {
                    Hint(strings.text(R.string.ui_tap_a_provider_to_fill_its_address_0c3ae))
                    ChipRow {
                        PROVIDER_PRESETS.forEach { preset ->
                            FilterChip(
                                selected = baseUrl.trim() == preset.baseUrl,
                                onClick = {
                                    baseUrl = preset.baseUrl
                                    protocol = preset.searchProtocol
                                    apiProtocol = AiApiProtocol.fromLegacy(preset.searchProtocol)
                                    if (name.isBlank()) name = preset.name
                                    vm.invalidateModels()
                                    vm.clearWebSearchResult()
                                    vm.clearEditorError()
                                },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                }
                Field {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it; vm.invalidateModels(); vm.clearEditorError() },
                        label = { Text(strings.text(R.string.ui_api_url_29f5b)) },
                        placeholder = { Text("https://api.deepseek.com") },
                        supportingText = {
                            if (isCleartextUrl(baseUrl)) {
                                Text(strings.text(R.string.ui_http_sends_requests_and_keys_without_encryption_use_only_on_721a9), color = MaterialTheme.colorScheme.error)
                            } else {
                                Text(strings.text(R.string.ui_enter_your_provider_s_url_the_request_path_is_added_for_the_9359f))
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Field {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it.trim(); vm.invalidateModels(); vm.clearEditorError() },
                        label = { Text(if (hasStoredKey) strings.text(R.string.ui_api_key_leave_blank_to_keep_47c01) else strings.text(R.string.ui_api_key_5df1d)) },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showKey) strings.text(R.string.ui_hide_key_6fe63) else strings.text(R.string.ui_show_key_cdb68),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsSection(strings.text(R.string.ui_models_98fd0)) {
                Field {
                    OutlinedButton(
                        onClick = { vm.fetchModels(baseUrl, apiKey, profile?.id, protocol) },
                        enabled = !editor.modelsBusy && baseUrl.isNotBlank() && (apiKey.isNotBlank() || hasStoredKey),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (editor.modelsBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (editor.modelsBusy) strings.text(R.string.ui_loading_6f4d0) else strings.text(R.string.ui_fetch_models_941d9))
                    }
                }
                editor.fetchMessage?.let { Hint(it) }
                ModelNameField(
                    value = model,
                    models = editor.models,
                    onValueChange = { model = it; vm.clearEditorError() },
                )
                Field {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; vm.clearEditorError() },
                        label = { Text(strings.text(R.string.ui_configuration_name_a5f08)) },
                        placeholder = { Text(strings.text(R.string.ui_leave_blank_to_use_model_name_7c6fe)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SwitchRow(
                    label = strings.text(R.string.ui_vision_image_support_f6d16),
                    subtitle = strings.text(R.string.ui_send_photos_directly_when_enabled_otherwise_choose_a_separat_c1270),
                    checked = vision,
                    onCheckedChange = { vision = it },
                )
                Text(
                    strings.text(R.string.ui_reasoning_effort_6164c),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                ChipRow {
                    AiReasoningEffort.entries.forEach { value ->
                        FilterChip(
                            selected = effort == value,
                            onClick = { effort = value },
                            label = { Text(reasoningLabel(value, strings)) },
                        )
                    }
                }
                Hint(com.moge.app.data.llm.ModelGenerationParameters.reasoningStatus(baseUrl, model, effort,
                    apiProtocol, editor.capabilities[model]))
            }

            SettingsSection(strings.text(R.string.ui_api_protocol_e56c5)) {
                Hint(strings.text(R.string.ui_choose_chat_completions_or_responses_to_match_your_provider_a64f2))
                ChipRow {
                    AiApiProtocol.entries.forEach { value ->
                        FilterChip(selected = apiProtocol == value, onClick = {
                            apiProtocol = value
                            protocol = when (value) {
                                AiApiProtocol.CHAT_COMPLETIONS -> AiSearchProtocol.CHAT_COMPLETIONS
                                AiApiProtocol.RESPONSES -> AiSearchProtocol.RESPONSES
                            }
                            vm.invalidateModels(); vm.clearWebSearchResult()
                        }, label = { Text(when (value) {
                            AiApiProtocol.CHAT_COMPLETIONS -> "Chat Completions"
                            AiApiProtocol.RESPONSES -> "Responses"
                        }) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = searchEnabled, onCheckedChange = { searchEnabled = it })
                    Text(strings.text(R.string.ui_allow_web_search_for_this_api_23c5d))
                }
                Hint(strings.text(R.string.ui_attachments_use_page_reading_tools_enable_vision_for_charts_3fc90))
                Field {
                    OutlinedButton(
                        onClick = { vm.testWebSearch(draft(), profile?.id) },
                        enabled = !editor.webSearchBusy && baseUrl.isNotBlank() && model.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (editor.webSearchBusy) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(if (editor.webSearchBusy) strings.text(R.string.ui_checking_0eda5) else strings.text(R.string.ui_test_web_search_00008))
                    }
                }
                editor.webSearchResult?.let { StatusText(it) }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            editor.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text(strings.text(R.string.ui_cancel_4d0b4)) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { vm.saveProfile(profile?.id, draft(), onSaved = onClose) },
                    enabled = !editor.saving,
                ) { Text(strings.text(R.string.ui_save_test_cbd63)) }
            }
        }
    }
}

@Composable
private fun Field(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
}

/** 模型名：可手填；拿到列表后右侧出现「选择」，下拉始终给完整列表，不按输入过滤。 */
@Composable
private fun ModelNameField(value: String, models: List<String>, onValueChange: (String) -> Unit) {
    val strings = rememberAppStrings()
    var menuOpen by remember { mutableStateOf(false) }
    Field {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(strings.text(R.string.ui_model_name_5e65d)) },
            placeholder = { Text(strings.text(R.string.ui_select_or_enter_manually_3213d)) },
            singleLine = true,
            trailingIcon = if (models.isEmpty()) null else {
                { TextButton(onClick = { menuOpen = true }) { Text(strings.text(R.string.ui_select_70b20)) } }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            models.forEach { id ->
                val selected = id.equals(value.trim(), ignoreCase = true)
                DropdownMenuItem(
                    text = { Text(id, fontWeight = if (selected) FontWeight.Bold else null) },
                    onClick = { onValueChange(id); menuOpen = false },
                )
            }
        }
    }
}
