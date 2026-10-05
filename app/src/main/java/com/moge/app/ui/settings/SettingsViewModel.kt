package com.moge.app.ui.settings

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
import com.moge.app.data.prefs.Appearance
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.data.prefs.UserSettings
import com.moge.app.domain.SolveMode
import com.moge.app.runtime.AttachmentJanitor
import com.moge.app.runtime.DraftStore
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
    @param:IoDispatcher private val io: CoroutineDispatcher,
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

    fun setAppearance(value: Appearance) {
        viewModelScope.launch { settings.setAppearance(value) }
    }

    fun setDefaultSolveMode(value: SolveMode) {
        viewModelScope.launch { settings.setDefaultSolveMode(value) }
    }

    fun setAnswerFirst(value: Boolean) {
        viewModelScope.launch { settings.setAnswerFirst(value) }
    }

    fun setMaxContinuations(value: Int) {
        viewModelScope.launch { settings.setMaxContinuations(value) }
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
            reloadProfiles(message = "已切换到「${selected.name}」")
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
            reloadProfiles(message = if (name.isEmpty()) "模型配置已删除" else "已删除「$name」")
        }
    }

    fun setVisionProfile(id: String?) {
        viewModelScope.launch {
            withContext(io) { credentials.setQuestionVisionProfileId(id) }
            reloadProfiles(message = if (id == null) "已取消识题模型" else "已设置识题模型")
        }
    }

    fun testActiveProfile() {
        if (_models.value.testing) return
        viewModelScope.launch {
            _models.update { it.copy(testing = true, message = "正在测试当前模型…") }
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
        _editor.update { it.copy(models = emptyList(), modelsBusy = false, fetchMessage = null) }
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
        baseUrlError(baseUrl)?.let { error ->
            _editor.update { it.copy(fetchMessage = error) }
            return
        }
        fetchJob?.cancel()
        fetchJob = viewModelScope.launch {
            _editor.update { it.copy(modelsBusy = true, fetchMessage = null) }
            try {
                val list = modelClient.fetchModels(baseUrl, apiKey, profileId, protocol)
                _editor.update { it.copy(models = list, fetchMessage = "已获取 ${list.size} 个模型") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _editor.update {
                    it.copy(fetchMessage = "${e.message ?: "获取模型列表失败"}；模型名可以手动填写")
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
            _editor.update { it.copy(webSearchBusy = true, webSearchResult = "正在检测联网搜索…") }
            val result = runReported(prefix = "检测失败：") {
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
        validateDraft(draft, hasStoredKey)?.let { error ->
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
                _editor.update { it.copy(saving = false, error = e.message ?: "保存失败") }
                return@launch
            }
            _editor.update { it.copy(saving = false) }
            onSaved()
            reloadProfiles(message = "已保存「${saved.name}」，正在测试连接…")
            _models.update { it.copy(testing = true) }
            val result = runReported { modelClient.testConnection(saved.id) }
            _models.update { it.copy(testing = false, message = result) }
        }
    }

    // ---------------- 数据 ----------------

    fun clearFigureCache() {
        runDataTask {
            val freed = withContext(io) { plots.clearPngs() + diagrams.clearPngs() }
            _cacheMessage.value = "已清理 ${formatBytes(freed)}，图表下次显示时重新绘制"
        }
    }

    /** 用户确认后才调用。重新扫一遍引用再删，不按之前统计的名单删。 */
    fun clearOrphans() {
        runDataTask {
            val freed = janitor.sweep()
            _cacheMessage.value = if (freed == null) {
                "无法确认哪些文件仍在使用，本次没有删除任何文件"
            } else {
                "已释放 ${formatBytes(freed)}"
            }
        }
    }

    /** 用户确认后才调用。生成中的题由数据库事务挡住，不会被删。 */
    fun clearNotebook() {
        runDataTask {
            notebook.clear()
            _cacheMessage.value = "已清空题册收藏，历史对话与分类已保留"
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
                        _cacheMessage.value = e.message ?: "操作失败"
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
            prefix + (e.message ?: "测试失败")
        }
}
