package com.moge.app.ui.settings

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
    var pendingDelete by remember { mutableStateOf<AiModelProfile?>(null) }
    val selected = state.profiles.firstOrNull { it.id == state.activeId }
    SettingsSection("模型") {
        Hint("填任意 OpenAI 兼容接口。密钥由本机密钥库加密，不进系统备份。")
        ModelProfileDropdown(
            label = "当前模型",
            choices = state.profiles.map { ModelChoice(it.id, it.name, profileSummary(it)) },
            selectedId = state.activeId,
            placeholder = if (state.profiles.isEmpty()) "还没有模型，先添加一个。" else "请选择模型",
            enabled = !state.testing,
            tag = "primary-model-selector",
            onSelect = { id -> id?.let(onSelect) },
        )
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(shape = MaterialTheme.shapes.extraSmall, onClick = onAdd, enabled = !state.testing,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加模型")
            }
            if (selected != null) {
                OutlinedButton(shape = MaterialTheme.shapes.extraSmall, onClick = onTest, enabled = !state.testing,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(if (state.testing) "测试中…" else "测试连接")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { onEdit(selected.id) }, enabled = !state.testing) {
                        Icon(Icons.Outlined.Edit, "编辑「${selected.name}」")
                    }
                    IconButton(onClick = { pendingDelete = selected }, enabled = !state.testing) {
                        Icon(Icons.Outlined.Delete, "删除「${selected.name}」", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        state.message?.let { StatusText(it) }
    }
    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除模型配置？") },
            text = { Text("将删除「${profile.name}」和它在本机保存的 API 密钥。") },
            confirmButton = { TextButton(onClick = { onDelete(profile.id); pendingDelete = null }) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
internal fun VisionSection(state: ModelsUiState, onSelect: (String?) -> Unit) {
    val candidates = state.profiles.filter { it.visionEnabled }
    SettingsSection("识题模型") {
        Hint("主模型不看图时，先由识题模型把照片转写成文字。可选择开启了「多模态」的配置。")
        ModelProfileDropdown(
            label = "照片识题",
            choices = listOf(ModelChoice(null, "不指定", "使用当前模型的看图能力")) +
                candidates.map { ModelChoice(it.id, it.name, it.model) },
            selectedId = state.visionProfileId,
            placeholder = "请选择识题模型",
            enabled = !state.testing,
            tag = "vision-model-selector",
            onSelect = onSelect,
        )
        StatusText(photoRouteHint(state.profiles, state.activeId, state.visionProfileId))
    }
}
