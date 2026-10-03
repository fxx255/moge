package com.moge.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.moge.app.data.credential.AiModelProfile
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.SolveMode
import com.moge.app.ui.components.PaperScaffold
import com.moge.app.ui.components.paperCard
import com.moge.app.ui.theme.MogeTheme
import com.moge.app.ui.update.AppUpdateDialog
import kotlinx.coroutines.delay

/** 新建配置时编辑目标的占位 id（真实 id 是 UUID，不会撞上）。 */
private const val NEW_PROFILE = "__new__"

/** 设置：模型配置、识题模型、解题偏好、外观、数据。模型编辑页盖在列表上，返回键先关它。 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
    onOpenNotebook: (() -> Unit)? = null,
) {
    val models by vm.models.collectAsStateWithLifecycle()
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var updating by rememberSaveable { mutableStateOf(false) }

    val editing = editingId
    if (editing != null) {
        val profile = models.profiles.firstOrNull { it.id == editing }
        BackHandler { editingId = null }
        ModelEditorScreen(
            profile = profile,
            vm = vm,
            onClose = { editingId = null },
        )
        return
    }

    val prefs by vm.userSettings.collectAsStateWithLifecycle()
    val cacheBytes by vm.figureCacheBytes.collectAsStateWithLifecycle()
    val cacheMessage by vm.cacheMessage.collectAsStateWithLifecycle()
    val orphanBytes by vm.orphanBytes.collectAsStateWithLifecycle()
    val dataBusy by vm.dataBusy.collectAsStateWithLifecycle()

    PaperScaffold(title = "设置", onBack = onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onOpenNotebook != null) SettingsSection("我的题册") {
                TextButton(onClick = onOpenNotebook, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.padding(end = 8.dp))
                    Text("查看收藏的题目与解答")
                }
            }
            ModelsSection(
                state = models,
                onSelect = vm::selectProfile,
                onEdit = { vm.openEditor(); editingId = it },
                onAdd = { vm.openEditor(); editingId = NEW_PROFILE },
                onDelete = vm::deleteProfile,
                onTest = vm::testActiveProfile,
            )
            VisionSection(state = models, onSelect = vm::setVisionProfile)
            PreferencesSection(
                prefs = prefs,
                onMode = vm::setDefaultSolveMode,
                onContinuations = vm::setMaxContinuations,
                onWebSearch = vm::setWebSearchEnabled,
                onNickname = vm::setNickname,
                onAnswerFirst = vm::setAnswerFirst,
            )
            SettingsSection("外观") {
                Column(Modifier.selectableGroup()) {
                    AppearanceOption("跟随系统", Appearance.SYSTEM, prefs.appearance, vm::setAppearance)
                    AppearanceOption("方格本（日间）", Appearance.PAPER, prefs.appearance, vm::setAppearance)
                    AppearanceOption("黑板（夜间）", Appearance.CHALK, prefs.appearance, vm::setAppearance)
                }
            }
            DataSection(
                cacheBytes = cacheBytes,
                orphanBytes = orphanBytes,
                busy = dataBusy,
                message = cacheMessage,
                onClearCache = vm::clearFigureCache,
                onClearOrphans = vm::clearOrphans,
                onClearNotebook = vm::clearNotebook,
            )
            SettingsSection("关于与更新") {
                TextButton(onClick = { updating = true }) { Text("版本与检查更新") }
            }
        }
    }
    if (updating) AppUpdateDialog(onDismiss = { updating = false })
}
@Composable
private fun ModelsSection(
    state: ModelsUiState,
    onSelect: (String) -> Unit,
    onEdit: (String) -> Unit,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onTest: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<AiModelProfile?>(null) }
    SettingsSection("模型") {
        Hint("填任意 OpenAI 兼容接口。密钥由本机密钥库加密，不进系统备份。")
        if (state.profiles.isEmpty()) {
            Text(
                "还没有模型，先添加一个。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        Column(Modifier.selectableGroup()) {
            state.profiles.forEach { profile ->
                ProfileRow(
                    profile = profile,
                    active = profile.id == state.activeId,
                    enabled = !state.testing,
                    onSelect = { onSelect(profile.id) },
                    onEdit = { onEdit(profile.id) },
                    onDelete = { pendingDelete = profile },
                )
            }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = onAdd, enabled = !state.testing) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("添加模型")
            }
            if (state.activeId != null) {
                OutlinedButton(onClick = onTest, enabled = !state.testing) {
                    Text(if (state.testing) "测试中…" else "测试连接")
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
            confirmButton = {
                TextButton(onClick = { onDelete(profile.id); pendingDelete = null }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProfileRow(
    profile: AiModelProfile,
    active: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = active, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = active, onClick = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(profile.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(
                profileSummary(profile),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        IconButton(onClick = onEdit, enabled = enabled) {
            Icon(Icons.Outlined.Edit, contentDescription = "编辑「${profile.name}」")
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除「${profile.name}」",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun VisionSection(state: ModelsUiState, onSelect: (String?) -> Unit) {
    val candidates = state.profiles.filter { it.visionEnabled }
    SettingsSection("识题模型") {
        Hint("主模型不看图时，先由识题模型把照片转写成文字。只列出开启了「多模态」的配置。")
        Column(Modifier.selectableGroup()) {
            ChoiceRow("不指定", selected = state.visionProfileId == null) { onSelect(null) }
            candidates.forEach { profile ->
                ChoiceRow(
                    "${profile.name}（${profile.model}）",
                    selected = profile.id == state.visionProfileId,
                ) { onSelect(profile.id) }
            }
        }
        StatusText(photoRouteHint(state.profiles, state.activeId, state.visionProfileId))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferencesSection(
    prefs: UserSettings,
    onMode: (SolveMode) -> Unit,
    onContinuations: (Int) -> Unit,
    onWebSearch: (Boolean) -> Unit,
    onNickname: (String) -> Unit,
    onAnswerFirst: (Boolean) -> Unit,
) {
    SettingsSection("解题偏好") {
        Text(
            "默认模式",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ChipRow {
            SolveMode.entries.forEach { mode ->
                FilterChip(
                    selected = prefs.defaultSolveMode == mode,
                    onClick = { onMode(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        SwitchRow(
            label = "答案优先",
            subtitle = "先看答案，点击展开完整解答；展开无需再次生成",
            checked = prefs.answerFirst,
            onCheckedChange = onAnswerFirst,
        )

        var continuations by remember(prefs.maxContinuations) { mutableStateOf(prefs.maxContinuations.toFloat()) }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("长回答自动续写", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            val n = continuations.toInt()
            Text(if (n == 0) "关闭" else "最多 $n 次", style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = continuations,
            onValueChange = { continuations = it },
            onValueChangeFinished = { onContinuations(continuations.toInt()) },
            valueRange = 0f..UserSettings.MAX_CONTINUATIONS.toFloat(),
            steps = UserSettings.MAX_CONTINUATIONS - 1,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .semantics { contentDescription = "长回答自动续写次数" },
        )
        Hint("回答撞到模型输出上限时自动接着写。")

        SwitchRow(
            label = "联网搜索",
            subtitle = "开启后由模型和服务端决定是否检索；协议在每个模型配置里选",
            checked = prefs.webSearchEnabled,
            onCheckedChange = onWebSearch,
        )

        NicknameField(prefs.nickname, onNickname)
    }
}

/** 称呼输入：本地先改，停顿 600ms 才写盘，避免每个字都落一次 DataStore。 */
@Composable
private fun NicknameField(saved: String, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(saved) }
    var edited by rememberSaveable { mutableStateOf(false) }
    // 首帧拿到的是默认值，DataStore 读完后再同步一次；用户动过就不再覆盖
    LaunchedEffect(saved) { if (!edited) text = saved }
    LaunchedEffect(text) {
        if (!edited) return@LaunchedEffect
        if (text.trim() == saved) return@LaunchedEffect
        delay(600)
        onSave(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it.take(16); edited = true },
        label = { Text("称呼（可选）") },
        placeholder = { Text("回答里怎么称呼你") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** 数据区要二次确认的操作。 */
private enum class DataAction { ORPHANS, NOTEBOOK }

@Composable
private fun DataSection(
    cacheBytes: Long?,
    orphanBytes: Long?,
    busy: Boolean,
    message: String?,
    onClearCache: () -> Unit,
    onClearOrphans: () -> Unit,
    onClearNotebook: () -> Unit,
) {
    var pending by remember { mutableStateOf<DataAction?>(null) }
    SettingsSection("数据") {
        DataRow("图表缓存", cacheBytes?.let(::formatBytes) ?: "计算中…") {
            OutlinedButton(onClick = onClearCache, enabled = !busy && (cacheBytes ?: 0L) > 0L) { Text("清理") }
        }
        Hint("只删渲染出的图片，题目里的图表下次打开时会重新画。")
        DataRow("未使用的照片和图表", orphanBytes?.let { "可释放 ${formatBytes(it)}" } ?: "计算中…") {
            OutlinedButton(
                onClick = { pending = DataAction.ORPHANS },
                enabled = !busy && (orphanBytes ?: 0L) > 0L,
            ) { Text("清理") }
        }
        Hint("已删除的题目留下的文件。最近 24 小时内拍的照片不会动。")
        DataRow("清空题册", "移除全部收藏，保留历史对话和分类") {
            OutlinedButton(
                onClick = { pending = DataAction.NOTEBOOK },
                enabled = !busy,
            ) { Text("清空", color = if (busy) Color.Unspecified else MaterialTheme.colorScheme.error) }
        }
        message?.let { StatusText(it) }
    }

    when (pending) {
        DataAction.ORPHANS -> ConfirmDialog(
            title = "清理未使用的文件？",
            text = "将删除不再被任何题目或草稿使用的照片和图表，约 ${formatBytes(orphanBytes ?: 0L)}。删除后无法恢复。",
            confirm = "清理",
            onConfirm = onClearOrphans,
            onDismiss = { pending = null },
        )
        DataAction.NOTEBOOK -> ConfirmDialog(
            title = "清空题册？",
            text = "将移除全部收藏，无法恢复。历史对话与自定义分类会保留；不再使用的照片与图表可另行清理。",
            confirm = "清空",
            onConfirm = onClearNotebook,
            onDismiss = { pending = null },
        )
        null -> Unit
    }
}

@Composable
private fun DataRow(title: String, detail: String, action: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        action()
    }
}

/** 不可逆操作的确认框：确认按钮用错误色，点外面或「取消」都不执行。 */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .paperCard(MaterialTheme.colorScheme.surface, MogeTheme.paper.cardStroke)
            .padding(vertical = 12.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        content()
    }
}

@Composable
internal fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

@Composable
internal fun StatusText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** 整行可切换的开关；Switch 本身不再单独响应，读屏只播报一次。 */
@Composable
internal fun SwitchRow(
    label: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 整行可点，RadioButton 本身不再单独响应，避免读屏重复播报
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun AppearanceOption(
    label: String,
    value: Appearance,
    selected: Appearance,
    onSelect: (Appearance) -> Unit,
) = ChoiceRow(label, selected = value == selected) { onSelect(value) }
