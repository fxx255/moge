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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            SettingsSection("使用偏好") {
                ConversationScreenOnSetting(prefs.keepScreenOnInConversation, vm::setKeepScreenOnInConversation)
                SwitchRow("题目图像预处理", "解题照片直接展平书页并净化背景；对话照片不处理",
                    prefs.questionPhotoPreprocessing, vm::setQuestionPhotoPreprocessing)
            }
            PreferencesSection(
                prefs = prefs,
                onMode = vm::setDefaultSolveMode,
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
                prefs = prefs,
                onAutoCleanup = vm::setHistoryAutoCleanupEnabled,
                onRetentionDays = vm::setHistoryRetentionDays,
            )
            BackgroundGenerationSection()
            SettingsSection("关于与更新") {
                TextButton(onClick = { updating = true }) { Text("版本与检查更新") }
            }
        }
    }
    if (updating) AppUpdateDialog(onDismiss = { updating = false })
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferencesSection(
    prefs: UserSettings,
    onMode: (SolveMode) -> Unit,
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
    prefs: UserSettings,
    onAutoCleanup: (Boolean) -> Unit,
    onRetentionDays: (Int) -> Unit,
) {
    var pending by remember { mutableStateOf<DataAction?>(null) }
    SettingsSection("数据") {
        HistoryRetentionControls(prefs, onAutoCleanup, onRetentionDays)
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
internal fun HistoryRetentionControls(
    prefs: UserSettings,
    onAutoCleanup: (Boolean) -> Unit,
    onRetentionDays: (Int) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    SwitchRow("自动清理历史对话", "仅清理未收藏、未分类的过期对话", prefs.historyAutoCleanupEnabled, onAutoCleanup)
    DataRow("保留期限", "从最近一次更新起计算；收藏或分类后长期保留") {
        OutlinedButton(onClick = { editing = true }, enabled = prefs.historyAutoCleanupEnabled) {
            Text("${prefs.historyRetentionDays} 天")
        }
    }
    if (editing && prefs.historyAutoCleanupEnabled) {
        HistoryRetentionDaysDialog(prefs.historyRetentionDays, onDismiss = { editing = false }, onSave = {
            editing = false
            onRetentionDays(it)
        })
    }
}

@Composable
private fun HistoryRetentionDaysDialog(days: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var input by rememberSaveable { mutableStateOf(days.toString()) }
    val value = input.toIntOrNull()?.takeIf { it in 1..UserSettings.MAX_HISTORY_RETENTION_DAYS }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("历史保留期限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("超过期限的未收藏、未分类对话会自动删除，收藏和分类内容长期保留。")
                OutlinedTextField(input, onValueChange = { input = it.filter(Char::isDigit).take(3) },
                    label = { Text("保留天数") }, suffix = { Text("天") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    isError = value == null, supportingText = { Text("可设置 1–365 天") })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 7, 14, 30, 90).forEach { preset ->
                        FilterChip(value == preset, onClick = { input = preset.toString() }, label = { Text("$preset 天") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text("保存") } },
        dismissButton = { TextButton(onDismiss) { Text("取消") } },
    )
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
internal fun ConversationScreenOnSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    SwitchRow("对话中屏幕常亮", subtitle = null, checked = checked, onCheckedChange = onCheckedChange)
}

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
