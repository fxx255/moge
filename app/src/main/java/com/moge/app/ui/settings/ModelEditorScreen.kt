package com.moge.app.ui.settings

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

    fun draft() = ModelProfileDraft(name, baseUrl, model, apiKey, vision, when (apiProtocol) { AiApiProtocol.CHAT_COMPLETIONS -> AiSearchProtocol.CHAT_COMPLETIONS; AiApiProtocol.RESPONSES -> AiSearchProtocol.RESPONSES }, effort, apiProtocol, searchEnabled)

    PaperScaffold(
        title = if (profile == null) "添加模型" else "编辑模型",
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
            SettingsSection("接口") {
                if (profile == null) {
                    Hint("常用服务：点一下填入地址")
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
                        label = { Text("接口地址") },
                        placeholder = { Text("https://api.deepseek.com") },
                        supportingText = {
                            if (isCleartextUrl(baseUrl)) {
                                Text("http 地址：请求和密钥会明文传输，只建议在局域网内用。", color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("填写服务的接口地址即可，会按所选协议补全请求路径。")
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
                        onValueChange = { apiKey = it.trim(); vm.clearEditorError() },
                        label = { Text(if (hasStoredKey) "API 密钥（留空则沿用）" else "API 密钥") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    if (showKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = if (showKey) "隐藏密钥" else "显示密钥",
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            SettingsSection("模型") {
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
                        Text(if (editor.modelsBusy) "正在获取…" else "获取模型列表")
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
                        label = { Text("配置名称") },
                        placeholder = { Text("留空则用模型名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                SwitchRow(
                    label = "多模态（可看图）",
                    subtitle = "开启后拍题直接发图；关闭则需要另配一个识题模型",
                    checked = vision,
                    onCheckedChange = { vision = it },
                )
                Text(
                    "思考强度",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                ChipRow {
                    AiReasoningEffort.entries.forEach { value ->
                        FilterChip(
                            selected = effort == value,
                            onClick = { effort = value },
                            label = { Text(reasoningLabel(value)) },
                        )
                    }
                }
            }

            SettingsSection("接口协议") {
                Hint("按服务实际接口选择 Chat Completions 或 Responses。关闭联网不会切换接口。")
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
                    Text("允许此接口使用联网搜索")
                }
                Hint("附件使用按页读取工具；图表和扫描件需要开启模型看图能力。")
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
                        Text(if (editor.webSearchBusy) "正在检测…" else "测试联网")
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
                TextButton(onClick = onClose) { Text("取消") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { vm.saveProfile(profile?.id, draft(), onSaved = onClose) },
                    enabled = !editor.saving,
                ) { Text("保存并测试") }
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
    var menuOpen by remember { mutableStateOf(false) }
    Field {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("模型名") },
            placeholder = { Text("选择或手动输入") },
            singleLine = true,
            trailingIcon = if (models.isEmpty()) null else {
                { TextButton(onClick = { menuOpen = true }) { Text("选择") } }
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
