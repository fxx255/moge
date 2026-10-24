package com.moge.app.ui.settings

import com.moge.app.ui.localization.label
import com.moge.app.R
import com.moge.app.ui.localization.rememberAppStrings

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
    val strings = rememberAppStrings()
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

    PaperScaffold(title = strings.text(R.string.ui_settings_7debf), onBack = onBack) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (onOpenNotebook != null) SettingsSection(strings.text(R.string.ui_my_notebook_7ec53)) {
                TextButton(onClick = onOpenNotebook, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.padding(end = 8.dp))
                    Text(strings.text(R.string.ui_view_saved_questions_and_answers_9b110))
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
            SettingsSection(strings.text(R.string.ui_preferences_9a18b)) {
                ConversationScreenOnSetting(prefs.keepScreenOnInConversation, vm::setKeepScreenOnInConversation)
                SwitchRow(strings.text(R.string.ui_clean_up_question_photos_921de), strings.text(R.string.ui_clean_up_question_photos_after_cropping_chat_photos_stay_as_f7f23),
                    prefs.questionPhotoPreprocessing, vm::setQuestionPhotoPreprocessing)
            }
            PreferencesSection(
                prefs = prefs,
                onMode = vm::setDefaultSolveMode,
                onWebSearch = vm::setWebSearchEnabled,
                onNickname = vm::setNickname,
                onAnswerFirst = vm::setAnswerFirst,
            )
            SettingsSection(strings.text(R.string.ui_appearance_09b58)) {
                Column(Modifier.selectableGroup()) {
                    AppearanceOption(strings.text(R.string.ui_follow_system_f4bbd), Appearance.SYSTEM, prefs.appearance, vm::setAppearance)
                    AppearanceOption(strings.text(R.string.ui_graph_paper_light_f6ce4), Appearance.PAPER, prefs.appearance, vm::setAppearance)
                    AppearanceOption(strings.text(R.string.ui_blackboard_dark_857f4), Appearance.CHALK, prefs.appearance, vm::setAppearance)
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
            BackgroundGenerationSection(report = vm::backgroundGenerationReport)
            LanguageFontSettingsSection(prefs, vm::setInterfaceLanguage, vm::setAnswerLanguage, vm::setFont)
            SettingsSection(strings.text(R.string.ui_about_updates_0902e)) {
                TextButton(onClick = { updating = true }) { Text(strings.text(R.string.ui_version_check_for_updates_a5dab)) }
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
    val strings = rememberAppStrings()
    SettingsSection(strings.text(R.string.ui_solution_preferences_d842b)) {
        Text(
            strings.text(R.string.ui_default_mode_c8b07),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ChipRow {
            SolveMode.entries.forEach { mode ->
                FilterChip(
                    selected = prefs.defaultSolveMode == mode,
                    onClick = { onMode(mode) },
                    label = { Text(strings.label(mode)) },
                )
            }
        }
        SwitchRow(
            label = strings.text(R.string.ui_answer_first_065b5),
            subtitle = strings.text(R.string.ui_show_the_answer_first_tap_to_expand_the_full_solution_withou_89cee),
            checked = prefs.answerFirst,
            onCheckedChange = onAnswerFirst,
        )

        SwitchRow(
            label = strings.text(R.string.ui_web_search_45e62),
            subtitle = strings.text(R.string.ui_the_model_and_provider_decide_when_to_search_choose_the_prot_ba8dd),
            checked = prefs.webSearchEnabled,
            onCheckedChange = onWebSearch,
        )

        NicknameField(prefs.nickname, onNickname)
    }
}

/** 称呼输入：本地先改，停顿 600ms 才写盘，避免每个字都落一次 DataStore。 */
@Composable
private fun NicknameField(saved: String, onSave: (String) -> Unit) {
    val strings = rememberAppStrings()
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
        label = { Text(strings.text(R.string.ui_preferred_name_optional_47ad2)) },
        placeholder = { Text(strings.text(R.string.ui_how_should_the_assistant_address_you_410da)) },
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
    val strings = rememberAppStrings()
    var pending by remember { mutableStateOf<DataAction?>(null) }
    SettingsSection(strings.text(R.string.ui_storage_data_54b8a)) {
        HistoryRetentionControls(prefs, onAutoCleanup, onRetentionDays)
        DataRow(strings.text(R.string.ui_chart_cache_1c1f9), cacheBytes?.let(::formatBytes) ?: strings.text(R.string.ui_calculating_70117)) {
            OutlinedButton(onClick = onClearCache, enabled = !busy && (cacheBytes ?: 0L) > 0L) { Text(strings.text(R.string.ui_clean_up_907c3)) }
        }
        Hint(strings.text(R.string.ui_delete_rendered_images_only_charts_are_redrawn_the_next_time_e0bf6))
        DataRow(strings.text(R.string.ui_unused_photos_charts_11ef3), orphanBytes?.let { strings.text(R.string.ui_1_s_can_be_freed_c74a1, formatBytes(it)) } ?: strings.text(R.string.ui_calculating_70117)) {
            OutlinedButton(
                onClick = { pending = DataAction.ORPHANS },
                enabled = !busy && (orphanBytes ?: 0L) > 0L,
            ) { Text(strings.text(R.string.ui_clean_up_907c3)) }
        }
        Hint(strings.text(R.string.ui_files_left_by_deleted_questions_photos_from_the_past_24_hour_0a0c3))
        DataRow(strings.text(R.string.ui_clear_notebook_39a63), strings.text(R.string.ui_remove_saved_items_keep_conversation_history_and_categories_cf660)) {
            OutlinedButton(
                onClick = { pending = DataAction.NOTEBOOK },
                enabled = !busy,
            ) { Text(strings.text(R.string.ui_clear_84fcd), color = if (busy) Color.Unspecified else MaterialTheme.colorScheme.error) }
        }
        message?.let { StatusText(it) }
    }

    when (pending) {
        DataAction.ORPHANS -> ConfirmDialog(
            title = strings.text(R.string.ui_delete_unused_files_17e8f),
            text = strings.text(R.string.ui_delete_about_1_s_of_photos_and_charts_no_longer_used_by_ques_7791c, formatBytes(orphanBytes ?: 0L)),
            confirm = strings.text(R.string.ui_clean_up_907c3),
            onConfirm = onClearOrphans,
            onDismiss = { pending = null },
        )
        DataAction.NOTEBOOK -> ConfirmDialog(
            title = strings.text(R.string.ui_clear_the_notebook_31a2b),
            text = strings.text(R.string.ui_remove_all_saved_items_permanently_conversation_history_and_bb0f1),
            confirm = strings.text(R.string.ui_clear_84fcd),
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
    val strings = rememberAppStrings()
    var editing by rememberSaveable { mutableStateOf(false) }
    SwitchRow(strings.text(R.string.ui_auto_delete_old_conversations_ebf42), strings.text(R.string.ui_only_delete_expired_conversations_that_are_neither_saved_nor_b2890), prefs.historyAutoCleanupEnabled, onAutoCleanup)
    DataRow(strings.text(R.string.ui_retention_period_f08e3), strings.text(R.string.ui_count_from_the_last_update_saved_or_categorized_conversation_d0820)) {
        OutlinedButton(onClick = { editing = true }, enabled = prefs.historyAutoCleanupEnabled) {
            Text(strings.text(R.string.ui_1_s_days_5d45f, prefs.historyRetentionDays))
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
    val strings = rememberAppStrings()
    var input by rememberSaveable { mutableStateOf(days.toString()) }
    val value = input.toIntOrNull()?.takeIf { it in 1..UserSettings.MAX_HISTORY_RETENTION_DAYS }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(R.string.ui_history_retention_b54be)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.text(R.string.ui_expired_conversations_are_deleted_unless_saved_or_categorize_d7fd3))
                OutlinedTextField(input, onValueChange = { input = it.filter(Char::isDigit).take(3) },
                    label = { Text(strings.text(R.string.ui_days_to_keep_6aea7)) }, suffix = { Text(strings.text(R.string.ui_days_c3304)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    isError = value == null, supportingText = { Text(strings.text(R.string.ui_choose_1_365_days_0679d)) })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(3, 7, 14, 30, 90).forEach { preset ->
                        FilterChip(value == preset, onClick = { input = preset.toString() }, label = { Text(strings.text(R.string.ui_1_s_days_5d45f, preset)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text(strings.text(R.string.ui_save_fadf2)) } },
        dismissButton = { TextButton(onDismiss) { Text(strings.text(R.string.ui_cancel_4d0b4)) } },
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
    val strings = rememberAppStrings()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirm, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.text(R.string.ui_cancel_4d0b4)) } },
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
    val strings = rememberAppStrings()
    SwitchRow(strings.text(R.string.ui_keep_screen_on_during_conversations_8cf60), subtitle = null, checked = checked, onCheckedChange = onCheckedChange)
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
