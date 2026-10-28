package com.moge.app.ui.settings

import com.moge.app.R
import com.moge.app.core.LocalizedText

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.core.IoDispatcher
import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiModelProfile
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.NotebookRepository
import com.moge.app.data.figure.DiagramImageStore
import com.moge.app.data.figure.PlotImageStore
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.llm.ModelCapability
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.AppLanguage
import com.moge.app.data.prefs.AppFont
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.SolveMode
import com.moge.app.runtime.AttachmentJanitor
import com.moge.app.runtime.DraftStore
import com.moge.app.runtime.BackgroundGenerationDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 模型配置区的界面状态。密钥永不出现在这里，只有 [AiModelProfile.hasApiKey]。 */
data class ModelsUiState(
    val profiles: List<AiModelProfile> = emptyList(),
    val activeId: String? = null,
    val visionProfileId: String? = null,
    /** 列表页底部的一句话结果（切换、删除、测试连接）。 */
    val message: String? = null,
    val testing: Boolean = false,
)

/** 编辑页的异步状态：模型列表、测试结果。随编辑页打开/关闭重置。 */
data class EditorUiState(
    val models: List<String> = emptyList(),
    val capabilities: Map<String, ModelCapability> = emptyMap(),
    val modelsBusy: Boolean = false,
    val fetchMessage: String? = null,
    val webSearchBusy: Boolean = false,
    val webSearchResult: String? = null,
    val saving: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val credentials: AiCredentialStore,
    private val modelClient: ModelClient,
    private val plots: PlotImageStore,
    private val diagrams: DiagramImageStore,
    private val janitor: AttachmentJanitor,
    private val conversations: ConversationRepository,
    private val drafts: DraftStore,
    private val notebook: NotebookRepository,
    private val backgroundDiagnostics: BackgroundGenerationDiagnostics,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    private val localizedText: LocalizedText,
) : ViewModel() {

    val userSettings: StateFlow<UserSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UserSettings())

    val appearance: StateFlow<Appearance> = settings.settings.map { it.appearance }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Appearance.SYSTEM)

    private val _models = MutableStateFlow(ModelsUiState())
    val models: StateFlow<ModelsUiState> = _models.asStateFlow()

    private val _editor = MutableStateFlow(EditorUiState())
    val editor: StateFlow<EditorUiState> = _editor.asStateFlow()

    /** 图表 PNG 占用；null 表示还没算完。 */
    private val _figureCacheBytes = MutableStateFlow<Long?>(null)
    val figureCacheBytes: StateFlow<Long?> = _figureCacheBytes.asStateFlow()

    private var fetchJob: Job? = null
    private var webSearchJob: Job? = null

    private val _cacheMessage = MutableStateFlow<String?>(null)
    val cacheMessage: StateFlow<String?> = _cacheMessage.asStateFlow()

    /** 未被引用、已过宽限期的照片与图表占用（只统计不删）；null 表示还没算完。 */
    private val _orphanBytes = MutableStateFlow<Long?>(null)
    val orphanBytes: StateFlow<Long?> = _orphanBytes.asStateFlow()

    /** 数据区有删除在跑时为 true，按钮全部禁用，避免两次删除交错。 */
    private val _dataBusy = MutableStateFlow(false)
    val dataBusy: StateFlow<Boolean> = _dataBusy.asStateFlow()

    init {
        viewModelScope.launch { reloadProfiles() }
        refreshFigureCache()
        refreshOrphans()
    }

    // ---------------- 偏好与外观 ----------------

    suspend fun backgroundGenerationReport(): String = withContext(io) { backgroundDiagnostics.report() }

    fun setAppearance(value: Appearance) {
        viewModelScope.launch { settings.setAppearance(value) }
    }

    fun setInterfaceLanguage(value: AppLanguage) {
        viewModelScope.launch { settings.setInterfaceLanguage(value) }
    }

    fun setAnswerLanguage(value: AppLanguage) {
        viewModelScope.launch { settings.setAnswerLanguage(value) }
    }

    fun setFont(value: AppFont) {
        viewModelScope.launch { settings.setFont(value) }
    }

    fun setDefaultSolveMode(value: SolveMode) {
        viewModelScope.launch { settings.setDefaultSolveMode(value) }
    }

    fun setAnswerFirst(value: Boolean) {
        viewModelScope.launch { settings.setAnswerFirst(value) }
    }

    fun setQuestionPhotoPreprocessing(value: Boolean) {
        viewModelScope.launch { settings.setQuestionPhotoPreprocessing(value) }
    }

    fun setKeepScreenOnInConversation(value: Boolean) {
        viewModelScope.launch { settings.setKeepScreenOnInConversation(value) }
    }

    fun setAutoResumeOnNetworkLoss(value: Boolean) {
        viewModelScope.launch { settings.setAutoResumeOnNetworkLoss(value) }
    }

    fun setHistoryAutoCleanupEnabled(value: Boolean) {
        viewModelScope.launch { settings.setHistoryAutoCleanupEnabled(value) }
    }

    fun setHistoryRetentionDays(value: Int) {
        viewModelScope.launch { settings.setHistoryRetentionDays(value) }
    }

    fun setWebSearchEnabled(value: Boolean) {
        viewModelScope.launch { settings.setWebSearchEnabled(value) }
    }

    fun setNickname(value: String) {
        viewModelScope.launch { settings.setNickname(value) }
    }

    // ---------------- 模型配置 ----------------

    fun selectProfile(id: String) {
        viewModelScope.launch {
            val selected = withContext(io) { credentials.selectProfile(id) } ?: return@launch
            reloadProfiles(message = localizedText.text(R.string.ui_switched_to_1_s_2a229, selected.name))
        }
    }

    fun deleteProfile(id: String) {
        viewModelScope.launch {
            val name = _models.value.profiles.firstOrNull { it.id == id }?.name.orEmpty()
            withContext(io) {
                credentials.deleteProfile(id)
                // 识题模型指向被删的配置时一并清掉，免得留下悬空 id
                if (credentials.questionVisionProfileId() == null) credentials.setQuestionVisionProfileId(null)
            }
            reloadProfiles(message = if (name.isEmpty()) localizedText.text(R.string.ui_model_configuration_deleted_94394) else localizedText.text(R.string.ui_deleted_1_s_ec1b0, name))
        }
    }

    fun setVisionProfile(id: String?) {
        viewModelScope.launch {
            withContext(io) { credentials.setQuestionVisionProfileId(id) }
            reloadProfiles(message = if (id == null) localizedText.text(R.string.ui_recognition_model_cleared_bc5e6) else localizedText.text(R.string.ui_recognition_model_set_aac7f))
        }
    }

    fun testActiveProfile() {
        if (_models.value.testing) return
        viewModelScope.launch {
            _models.update { it.copy(testing = true, message = localizedText.text(R.string.ui_testing_current_model_4e042)) }
            val result = runReported { modelClient.testConnection() }
            _models.update { it.copy(testing = false, message = result) }
        }
    }

    /** 打开编辑页时调用，清掉上一次的模型列表和测试结果。 */
    fun openEditor() {
        fetchJob?.cancel()
        webSearchJob?.cancel()
        _editor.value = EditorUiState()
    }

    /** 接口地址改了，旧列表不再可信。 */
    fun invalidateModels() {
        fetchJob?.cancel()
        _editor.update { it.copy(models = emptyList(), capabilities = emptyMap(), modelsBusy = false, fetchMessage = null) }
    }

    fun clearEditorError() {
        _editor.update { it.copy(error = null) }
    }

    fun clearWebSearchResult() {
        webSearchJob?.cancel()
        _editor.update { it.copy(webSearchBusy = false, webSearchResult = null) }
    }

    /**
     * 获取模型列表。[profileId] 让密钥框留空时回退到该配置已存的密钥。
     * 失败时保留上一次的列表，模型名仍可手填。
     */
    fun fetchModels(baseUrl: String, apiKey: String, profileId: String?, protocol: AiSearchProtocol? = null) {
        baseUrlError(baseUrl, localizedText)?.let { error ->
            _editor.update { it.copy(fetchMessage = error) }
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _editor.update { it.copy(modelsBusy = true, fetchMessage = null) }
            try {
                val catalog = modelClient.fetchModelCatalog(baseUrl, apiKey, profileId, protocol)
                _editor.update { it.copy(models = catalog.ids, capabilities = catalog.capabilities,
                    fetchMessage = localizedText.text(R.string.ui_loaded_1_s_models_2b7c7, catalog.ids.size)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editor.update {
                    it.copy(fetchMessage = localizedText.text(R.string.ui_1_s_you_can_enter_the_model_name_manually_aa0ab, e.message ?: localizedText.text(R.string.ui_could_not_fetch_model_list_ec876)))
                }
            } finally {
                _editor.update { it.copy(modelsBusy = false) }
            }
        }
    }

    /** 按编辑页里正在编辑的配置测联网，未保存的改动也能先测。 */
    fun testWebSearch(draft: ModelProfileDraft, profileId: String?) {
        webSearchJob?.cancel()
        webSearchJob = viewModelScope.launch {
            _editor.update { it.copy(webSearchBusy = true, webSearchResult = localizedText.text(R.string.ui_testing_web_search_b9318)) }
            val result = runReported(prefix = localizedText.text(R.string.ui_test_failed_c5b96)) {
                modelClient.testWebSearch(
                    baseUrl = draft.baseUrl,
                    apiKey = draft.apiKey,
                    model = draft.model,
                    protocol = draft.searchProtocol,
                    profileId = profileId,
                )
            }
            _editor.update { it.copy(webSearchBusy = false, webSearchResult = result) }
        }
    }

    /**
     * 保存并设为当前模型，然后测一次连通性。
     * 校验失败时停在编辑页显示错误；保存成功就调 [onSaved] 关掉编辑页，测试结果写到列表页。
     */
    fun saveProfile(id: String?, draft: ModelProfileDraft, onSaved: () -> Unit) {
        if (_editor.value.saving) return
        val hasStoredKey = id != null && _models.value.profiles.any { it.id == id && it.hasApiKey }
        validateDraft(draft, hasStoredKey, localizedText)?.let { error ->
            _editor.update { it.copy(error = error) }
            return
        }
        viewModelScope.launch {
            _editor.update { it.copy(saving = true, error = null) }
            val saved = try {
                withContext(io) {
                    credentials.upsertProfile(
                        id = id,
                        name = effectiveName(draft),
                        baseUrl = draft.baseUrl,
                        model = draft.model,
                        apiKey = draft.apiKey,
                        visionEnabled = draft.visionEnabled,
                        searchProtocol = draft.searchProtocol,
                        reasoningEffort = draft.reasoningEffort,
                        apiProtocol = draft.apiProtocol,
                        searchEnabled = draft.searchEnabled,
                    )
                }
            } catch (e: IllegalArgumentException) {
                _editor.update { it.copy(saving = false, error = e.message ?: localizedText.text(R.string.ui_save_failed_40525)) }
                return@launch
            }
            _editor.update { it.copy(saving = false) }
            onSaved()
            reloadProfiles(message = localizedText.text(R.string.ui_saved_1_s_testing_connection_57fa1, saved.name))
            _models.update { it.copy(testing = true) }
            val result = runReported { modelClient.testConnection(saved.id) }
            _models.update { it.copy(testing = false, message = result) }
        }
    }

    // ---------------- 数据 ----------------

    fun clearFigureCache() {
        runDataTask {
            val freed = withContext(io) { plots.clearPngs() + diagrams.clearPngs() }
            _cacheMessage.value = localizedText.text(R.string.ui_freed_1_s_charts_will_be_redrawn_when_opened_47dce, formatBytes(freed))
        }
    }

    /** 用户确认后才调用。重新扫一遍引用再删，不按之前统计的名单删。 */
    fun clearOrphans() {
        runDataTask {
            val freed = janitor.sweep()
            _cacheMessage.value = if (freed == null) {
                localizedText.text(R.string.ui_could_not_verify_file_usage_no_files_were_deleted_a2af5)
            } else {
                localizedText.text(R.string.ui_freed_1_s_57fc2, formatBytes(freed))
            }
        }
    }

    /** 用户确认后才调用。生成中的题由数据库事务挡住，不会被删。 */
    fun clearNotebook() {
        runDataTask {
            notebook.clear()
            _cacheMessage.value = localizedText.text(R.string.ui_notebook_cleared_conversation_history_and_categories_are_kep_ab04a)
        }
    }

    /** 数据区的删除一律串行、不随页面退出而中断，结束后重算占用。 */
    private fun runDataTask(block: suspend () -> Unit) {
        if (_dataBusy.value) return
        _dataBusy.value = true
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    try {
                        block()
                    } catch (e: Exception) {
                        _cacheMessage.value = e.message ?: localizedText.text(R.string.ui_operation_failed_09e42)
                    }
                }
            } finally {
                _dataBusy.value = false
            }
            refreshFigureCache()
            refreshOrphans()
        }
    }

    private fun refreshOrphans() {
        viewModelScope.launch {
            // 统计失败按 0 显示：按钮禁用，宁可不让删。
            _orphanBytes.value = janitor.sweep(dryRun = true) ?: 0L
        }
    }

    private fun refreshFigureCache() {
        viewModelScope.launch {
            _figureCacheBytes.value = withContext(io) { plots.pngBytes() + diagrams.pngBytes() }
        }
    }

    private suspend fun reloadProfiles(message: String? = _models.value.message) {
        val (profiles, activeId, visionId) = withContext(io) {
            Triple(credentials.profiles(), credentials.activeProfileId(), credentials.questionVisionProfileId())
        }
        _models.update {
            it.copy(profiles = profiles, activeId = activeId, visionProfileId = visionId, message = message)
        }
    }

    /** 把一次网络自检的成功/失败都折成一句给用户看的话。 */
    private suspend fun runReported(prefix: String = "", block: suspend () -> String): String =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefix + (e.message ?: localizedText.text(R.string.ui_test_failed_4184c))
        }
}
