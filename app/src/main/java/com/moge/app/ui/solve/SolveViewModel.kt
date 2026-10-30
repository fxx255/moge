package com.moge.app.ui.solve

import com.moge.app.R
import com.moge.app.core.LocalizedText

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.ConversationRepository.Companion.PHOTO_PLACEHOLDER_TITLE
import com.moge.app.data.db.MessageEntity
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.db.ConversationBranches
import com.moge.app.data.db.BranchSelectionEntity
import com.moge.app.data.llm.ModelException
import com.moge.app.data.llm.SnapshotCodec
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.FailureKind
import com.moge.app.domain.RequestStatus
import com.moge.app.domain.SolveMode
import com.moge.app.domain.ConversationMode
import com.moge.app.runtime.DraftStore
import com.moge.app.runtime.GenerationManager
import com.moge.app.runtime.QuestionPhotoPreparation
import com.moge.app.data.image.PhotoCropEdit
import com.moge.app.runtime.StoredFigureRepair
import com.moge.app.ui.Routes
import com.moge.app.ui.capture.CaptureBatch
import com.moge.app.ui.capture.CaptureStore
import com.moge.app.ui.components.PhotoArrivals
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** 解题页的完整界面状态。 */
data class SolveUiState(
    val conversationId: String? = null,
    val title: String = ConversationRepository.PLACEHOLDER_TITLE,
    val items: List<SolveItem> = emptyList(),
    val input: String = "",
    /** 这道题正在生成（发送键变停止键）。 */
    val generating: Boolean = false,
    /** 另一道题正在生成：本页暂时不能发送。 */
    val busyElsewhere: Boolean = false,
    /** 提交或重发的落盘还在路上（防连点）。 */
    val submitting: Boolean = false,
    /** 页面底部的一句话提示（提交失败、重发被拒等）；null 不显示。 */
    val notice: String? = null,
    /** 待随下一问发出的照片（追问时拍的 / 选的，或拍题首问提交失败后退回来的）。 */
    val photos: List<String> = emptyList(),
    val importingPhotos: Boolean = false,
    val documentPaths: List<String> = emptyList(),
    val answerFirst: Boolean = false,
    val keepScreenOnInConversation: Boolean = false,
    val editingQuestionId: String? = null,
    val editChanged: Boolean = true,
    val switchingBranch: Boolean = false,
    val branchPathKey: String = "",
    val conversationMode: ConversationMode = ConversationMode.SOLVE,
    val modeSelectable: Boolean = conversationId == null,
    /** Input and mode restoration must finish before either can be submitted. */
    val draftLoaded: Boolean = true,
) {
    val canSend: Boolean
        get() = (input.isNotBlank() || photos.isNotEmpty() || documentPaths.isNotEmpty()) && !generating && !busyElsewhere && !submitting && !importingPhotos &&
            !switchingBranch && draftLoaded && (editingQuestionId == null || editChanged)

    val canAddPhoto: Boolean
        get() = photos.size < CaptureStore.MAX_PHOTOS && !submitting && !importingPhotos && !switchingBranch
}

/**
 * 解题页 ViewModel。
 *
 * 生成本身归应用级 [GenerationManager]：这里只**提交**和**订阅**，页面销毁不会打断生成。
 * 列表 = Room 里的消息与请求记录 + 管理器的可重放活动状态，合成规则见 [buildSolveItems]。
 *
 * 从砺行 AssistantViewModel 移植了三条防护：
 * - 发送/重发用同步置位的 [submitting] 防连点，挂起窗口里再点不会提交第二次；
 * - 草稿只消费「发送那一刻的那份输入」：提交期间用户又改过（编辑序号变了）就保留新编辑；
 * - 重发前校验归属与附件，`beginRetry` 已提交而管理器没跑起来时回滚成可重试的中断。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SolveViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val generationManager: GenerationManager,
    private val requestRepository: RequestRepository,
    private val conversationRepository: ConversationRepository,
    private val draftStore: DraftStore,
    private val settings: SettingsRepository,
    private val captureStore: CaptureStore,
    private val storedFigureRepair: StoredFigureRepair,
    private val photoPreparation: QuestionPhotoPreparation,
    private val localizedText: LocalizedText,
) : ViewModel() {

    /** 当前会话；null = 还没发出第一问的新题目。建好后写回 [savedState]，旋转/重建后仍指向它。 */
    private val conversationId = MutableStateFlow(
        savedState.get<String>(Routes.ARG_CONVERSATION_ID)?.takeIf { it.isNotBlank() },
    )

    private val input = MutableStateFlow("")
    private val selectedMode = MutableStateFlow(ConversationMode.fromName(savedState.get<String>(Routes.ARG_DRAFT_MODE)))
    private val photos = MutableStateFlow<List<String>>(emptyList())
    private val importingPhotos = MutableStateFlow(false)
    private val photoPasteMutex = Mutex()
    private var pendingPhotoPastes = 0
    private val documents = MutableStateFlow<List<String>>(emptyList())
    private val submitting = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)

    /** 输入框（文字 + 待发照片）的编辑序号：每次改动 +1。发送成功后只有序号没变才清空输入与草稿。 */
    private var editRevision = 0L

    /** 用户在草稿恢复完成前就开始打字：恢复结果不得覆盖新输入。 */
    private var draftRestored = false
    private var draftSlot: String? = conversationId.value
    private val editing = MutableStateFlow<MessageEntity?>(null)
    private val switchingBranch = MutableStateFlow(false)
    private val draftReady = CompletableDeferred<Unit>()
    private val draftLoaded = MutableStateFlow(false)

    private data class RoomSnapshot(
        val conversation: ConversationEntity?,
        val messages: List<MessageEntity>,
        val requests: List<RequestEntity>,
        val selections: List<BranchSelectionEntity> = emptyList(),
    )

    private val roomState: Flow<RoomSnapshot> = conversationId.flatMapLatest { id ->
        if (id == null) {
            flowOf(RoomSnapshot(null, emptyList(), emptyList()))
        } else {
            combine(
                conversationRepository.observeTree(id),
                requestRepository.observeForConversation(id),
            ) { tree, requests -> RoomSnapshot(tree?.conversation, tree?.messages.orEmpty().sortedBy { it.createdAt },
                requests, tree?.selections.orEmpty()) }
                .onEach { storedFigureRepair.repair(it.messages, it.requests) }
        }
    }

    private data class Transient(
        val input: String,
        val photos: List<String>,
        val submitting: Boolean,
        val notice: String?,
        val documents: List<String>,
    )

    private val transient: Flow<Transient> =
        combine(input, photos, submitting, notice, documents) { text, pics, busy, message, docs -> Transient(text, pics, busy, message, docs) }

    private val documentImportMutex = Mutex()

    val uiState: StateFlow<SolveUiState> =
        combine(conversationId, roomState, generationManager.active, transient, settings.settings) { id, room, active, local, prefs ->
            val path = ConversationBranches.visible(room.messages, room.selections)
            // A retained navigation entry can outlive a deleted history record. Keep its
            // draft owner for recovery, but only a real record represents an existing page.
            val existingId = id?.takeIf { room.conversation?.id == it }
            val generatingHere = active.isRunning && id != null && active.conversationId == id && path.any { it.id == active.answerMessageId }
            SolveUiState(
                conversationId = existingId,
                title = room.conversation?.let { conversation ->
                    // Keep the stored placeholder stable for automatic naming; localize its display only.
                    if (conversation.title == PHOTO_PLACEHOLDER_TITLE && !conversation.titleUserEdited)
                        localizedText.text(prefs.interfaceLanguage, R.string.ui_photo_question_770f1)
                    else conversation.title
                } ?: ConversationRepository.PLACEHOLDER_TITLE,
                items = buildSolveItems(id, path, room.requests, active, room.messages),
                input = local.input,
                generating = generatingHere,
                busyElsewhere = active.isRunning && !generatingHere,
                submitting = local.submitting,
                notice = local.notice,
                photos = local.photos,
                documentPaths = local.documents,
                answerFirst = prefs.answerFirst,
                keepScreenOnInConversation = prefs.keepScreenOnInConversation,
                branchPathKey = path.lastOrNull()?.id.orEmpty(),
                conversationMode = room.conversation?.let { ConversationMode.fromName(it.conversationMode) } ?: selectedMode.value,
                modeSelectable = existingId == null && !local.submitting,
            )
        }.combine(selectedMode) { state, mode ->
            if (state.conversationId == null) state.copy(conversationMode = mode) else state
        }.combine(importingPhotos) { state, importing -> state.copy(importingPhotos = importing,
            modeSelectable = state.modeSelectable && !importing) }
            .combine(editing) { state, original -> state.copy(editingQuestionId = original?.id,
                editChanged = original == null || state.input.trim() != original.content.trim() ||
                    state.photos != RequestRepository.decodePathList(original.imagePaths) ||
                    state.documentPaths != RequestRepository.decodePathList(original.documentPaths)) }
            .combine(switchingBranch) { state, switching -> state.copy(switchingBranch = switching) }
            .combine(draftLoaded) { state, loaded -> state.copy(modeSelectable = state.modeSelectable && loaded,
                draftLoaded = loaded) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000),
                SolveUiState(conversationId.value, conversationMode = selectedMode.value, modeSelectable = false, draftLoaded = false))

    init {
        restoreDraft()
        viewModelScope.launch {
            draftReady.await()
            savedState.getStateFlow<String?>(Routes.ARG_CAPTURE, null).filterNotNull().collect { raw ->
                Routes.decodeCapture(raw)?.let { batch ->
                    addPhotos(batch.photoPaths)
                    importDocuments(batch.documentPaths)
                    if (batch.note.isNotBlank()) onInputChange(listOf(input.value, batch.note).filter { it.isNotBlank() }.joinToString("\n"))
                }
                savedState[Routes.ARG_CAPTURE] = null
            }
        }
    }

    // ---------------- 输入与草稿 ----------------

    /** 导航返回结果先交给本 ViewModel，再由恢复草稿后的串行消费者加入附件。 */
    fun onCaptureResult(raw: String) {
        savedState[Routes.ARG_CAPTURE] = raw
    }

    /** Merge an imported batch into the live branch draft, then persist before acknowledging it. */
    suspend fun acceptSharedImages(share: com.moge.app.runtime.SharedImages) {
        draftReady.await()
        check(!submitting.value && !switchingBranch.value && !importingPhotos.value) { "输入正在处理，请稍后重试" }
        val added = share.photoPaths.filterNot { it in photos.value }
        require(photos.value.size + added.size <= CaptureStore.MAX_PHOTOS) {
            "当前输入最多容纳 ${CaptureStore.MAX_PHOTOS} 张图片，请移除部分图片或选择新对话"
        }
        // A failed disk write may be retried while this ViewModel retains its updated input.
        if (added.isNotEmpty()) {
            photos.value = photos.value + added
            if (share.text.isNotBlank()) input.value = listOf(input.value, share.text).filter(String::isNotBlank).joinToString("\n")
            PhotoArrivals.markNew(added)
            editRevision++
            draftRestored = true
            preparePhotos(added)
        }
        draftStore.persist(draftStore.reserveSave(draftSlot, input.value, photos.value, documents.value, selectedMode.value))
    }

    private fun restoreDraft() {
        val key = conversationId.value
        val initialSlot = draftSlot
        viewModelScope.launch {
            var normalSlot = initialSlot
            var restoredSlot = initialSlot
            var target: MessageEntity? = null
            val draft = try {
                if (key != null) {
                    // Resolve before enabling send, including pages whose Room flow is not active yet.
                    conversationRepository.getConversation(key)?.let {
                        selectedMode.value = ConversationMode.fromName(it.conversationMode)
                    }
                    normalSlot = normalDraftSlot(key)
                    restoredSlot = normalSlot
                    val targetId = draftStore.load(editMetaSlot(key))?.text
                    target = targetId?.let { id -> conversationRepository.messages(key).firstOrNull { it.id == id && it.role == "user" } }
                    target?.let { restoredSlot = editDraftSlot(key, it.id) }
                }
                draftStore.load(restoredSlot) ?: target?.let { original -> DraftStore.Draft(original.content,
                    RequestRepository.decodePathList(original.imagePaths), RequestRepository.decodePathList(original.documentPaths)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // 草稿读不出来不影响解题，空输入框即可
            }
            if (!draftRestored && conversationId.value == key) {
                draftSlot = restoredSlot
                editing.value = target
                input.value = draft?.text.orEmpty()
                // 缺图引用照样恢复：发送时 persistAttachments 会明确报错，而不是悄悄丢图。
                photos.value = draft?.photoPaths.orEmpty().take(CaptureStore.MAX_PHOTOS)
                documents.value = draft?.documentPaths.orEmpty()
                if (key == null && savedState.get<String>(Routes.ARG_DRAFT_MODE) == null) {
                    selectedMode.value = draft?.conversationMode ?: ConversationMode.SOLVE
                }
            } else if (conversationId.value == key && draftSlot == initialSlot) {
                // Early typing belongs to the ordinary selected branch and supersedes recovery.
                draftSlot = normalSlot
                val reservation = draftStore.reserveSave(normalSlot, input.value, photos.value, documents.value, selectedMode.value)
                val clearOld = if (initialSlot != normalSlot) draftStore.reserveSave(initialSlot, "", emptyList()) else null
                runCatching {
                    draftStore.persist(reservation)
                    clearOld?.let { draftStore.persist(it) }
                    if (key != null && target != null) draftStore.clear(editMetaSlot(key))
                }
            }
            draftRestored = true
            draftLoaded.value = true
            // A navigation mode takes precedence over the shared draft without discarding its contents.
            if (key == null && savedState.get<String>(Routes.ARG_DRAFT_MODE) != null) saveDraft()
            draftReady.complete(Unit)
            preparePhotos(photos.value)
        }
    }

    fun onInputChange(text: String) {
        if (switchingBranch.value) return
        input.value = text
        saveDraft()
    }

    /** The inline editor owns focus; this callback only persists to the current conversation. */
    suspend fun renameTitle(title: String): Boolean {
        val id = conversationId.value ?: return false
        return try {
            conversationRepository.rename(id, title).also { renamed ->
                if (!renamed) notice.value = localizedText.text(R.string.ui_this_conversation_was_deleted_f7d12)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            notice.value = error.message ?: localizedText.text(R.string.ui_could_not_update_topic_try_again_e210b)
            false
        }
    }

    fun selectConversationMode(mode: ConversationMode) {
        if (!draftLoaded.value || !uiState.value.modeSelectable || submitting.value || importingPhotos.value) return
        requestNewConversationMode(mode)
    }

    /** Navigation may reuse an unsent page whose draft is still loading. */
    fun requestNewConversationMode(mode: ConversationMode) {
        if (uiState.value.conversationId != null || submitting.value) return
        selectedMode.value = mode
        savedState[Routes.ARG_DRAFT_MODE] = mode.name
        if (draftLoaded.value) {
            saveDraft()
            preparePhotos(photos.value)
        }
    }

    private fun editMetaSlot(id: String) = id + "__editing"
    private fun editDraftSlot(id: String, question: String) = id + "__edit__" + question
    private fun branchDraftSlot(id: String, tip: String?) = id + "__path__" + (tip ?: "root")

    private suspend fun normalDraftSlot(id: String): String {
        val all = conversationRepository.messages(id)
        val branched = all.any { it.parentMessageId != null } &&
            all.filter { it.role == "user" }.groupBy { it.parentMessageId }.values.any { it.size > 1 }
        return if (branched) branchDraftSlot(id, conversationRepository.visibleMessages(id).lastOrNull()?.id) else id
    }

    private fun showDraft(draft: DraftStore.Draft?) {
        input.value = draft?.text.orEmpty()
        photos.value = draft?.photoPaths.orEmpty().take(CaptureStore.MAX_PHOTOS)
        documents.value = draft?.documentPaths.orEmpty()
        draftRestored = true
        editRevision++
    }

    fun editQuestion(questionId: String) {
        val id = conversationId.value ?: return
        if (submitting.value || switchingBranch.value || importingPhotos.value || editing.value != null) return
        switchingBranch.value = true
        viewModelScope.launch {
            try {
                draftReady.await()
                val path = conversationRepository.visibleMessages(id)
                val original = path.firstOrNull { it.id == questionId && it.role == "user" } ?: return@launch
                draftStore.save(draftSlot, input.value, photos.value, documents.value)
                // A first edit creates the first fork, so also park a legacy conversation draft by its old tip.
                draftStore.save(branchDraftSlot(id, path.lastOrNull()?.id), input.value, photos.value, documents.value)
                val editSlot = editDraftSlot(id, questionId)
                val draft = draftStore.load(editSlot) ?: DraftStore.Draft(original.content,
                    RequestRepository.decodePathList(original.imagePaths), RequestRepository.decodePathList(original.documentPaths))
                draftStore.save(editSlot, draft.text, draft.photoPaths, draft.documentPaths)
                draftStore.save(editMetaSlot(id), questionId, emptyList())
                editing.value = original
                draftSlot = editSlot
                showDraft(draft)
                notice.value = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice.value = e.message ?: localizedText.text(R.string.ui_could_not_load_original_input_try_again_2efa5) }
            finally { switchingBranch.value = false }
        }
    }

    fun cancelEditing() {
        val id = conversationId.value ?: return
        val original = editing.value ?: return
        if (submitting.value || switchingBranch.value || importingPhotos.value) return
        switchingBranch.value = true
        viewModelScope.launch {
            try {
                val next = normalDraftSlot(id)
                val draft = draftStore.load(next)
                draftStore.clear(editMetaSlot(id))
                draftStore.clear(editDraftSlot(id, original.id))
                editing.value = null
                draftSlot = next
                showDraft(draft)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice.value = e.message ?: localizedText.text(R.string.ui_could_not_close_edit_draft_try_again_54494) }
            finally { switchingBranch.value = false }
        }
    }

    fun switchVersion(questionId: String) {
        val id = conversationId.value ?: return
        if (submitting.value || switchingBranch.value || importingPhotos.value || editing.value != null) return
        switchingBranch.value = true
        viewModelScope.launch {
            try {
                draftReady.await()
                draftStore.save(draftSlot, input.value, photos.value, documents.value)
                conversationRepository.selectBranch(id, questionId)
                val next = normalDraftSlot(id)
                val draft = draftStore.load(next)
                draftSlot = next
                showDraft(draft)
                notice.value = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice.value = e.message ?: localizedText.text(R.string.ui_could_not_switch_version_try_again_25270) }
            finally { switchingBranch.value = false }
        }
    }

    /** Import originals as document attachments; never modify the question text. */
    fun importDocument(uri: android.net.Uri) {
        if (switchingBranch.value || submitting.value) return
        pendingPhotoPastes++
        importingPhotos.value = true
        viewModelScope.launch {
            try {
                val path = captureStore.importDocument(uri)
                importDocuments(listOf(path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: localizedText.text(R.string.ui_document_import_failed_cce10)
            } finally {
                pendingPhotoPastes--
                importingPhotos.value = pendingPhotoPastes > 0
            }
        }
    }

    private suspend fun importDocuments(paths: List<String>) {
        if (paths.isEmpty()) return
        documentImportMutex.withLock {
            val remaining = com.moge.app.data.document.DocumentStore.MAX_ATTACHMENTS - documents.value.size
            documents.value = (documents.value + paths.take(remaining.coerceAtLeast(0))).distinct()
            saveDraft()
            if (paths.size > remaining) notice.value = localizedText.text(R.string.ui_up_to_8_documents_at_a_time_a5950)
        }
    }

    fun removeDocument(path: String) {
        if (switchingBranch.value) return
        documents.value = documents.value.filterNot { it == path }
        saveDraft()
    }

    /** 追问拍照的目标文件（落在私有 photos 目录，裁剪原地改写）。 */
    fun newCameraFile(): File = captureStore.newCameraFile()

    /** Resolve saved chat mode from storage even if the first UI snapshot has not arrived. */
    private suspend fun currentConversationMode(): ConversationMode {
        draftReady.await()
        val id = conversationId.value ?: return selectedMode.value
        val conversation = conversationRepository.getConversation(id) ?: return selectedMode.value
        return ConversationMode.fromName(conversation.conversationMode)
    }

    fun resolveConversationMode(onReady: (ConversationMode) -> Unit) {
        viewModelScope.launch {
            try {
                onReady(currentConversationMode())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice.value = localizedText.text(R.string.ui_could_not_read_conversation_mode_try_again_later_801de)
            }
        }
    }

    /** 追问时从相册选的图：复制进私有目录后交给页面逐张裁剪。 */
    fun importPicked(uris: List<android.net.Uri>, onImported: (List<String>) -> Unit) {
        if (uris.isEmpty() || switchingBranch.value || submitting.value) return
        val room = CaptureStore.MAX_PHOTOS - photos.value.size
        if (room <= 0) {
            notice.value = localizedText.text(R.string.ui_up_to_1_s_photos_per_question_5ed3f, CaptureStore.MAX_PHOTOS)
            return
        }
        pendingPhotoPastes++
        importingPhotos.value = true
        viewModelScope.launch {
            try {
                val imported = captureStore.importFromGallery(uris.take(room))
                onImported(imported)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: localizedText.text(R.string.ui_could_not_import_photo_72b85)
            } finally {
                pendingPhotoPastes--
                importingPhotos.value = pendingPhotoPastes > 0
            }
        }
    }

    /** Paste directly into the draft, without interrupting typing with a crop dialog. */
    fun pasteImages(uris: List<android.net.Uri>) {
        if (uris.isEmpty() || switchingBranch.value) return
        if (submitting.value) {
            notice.value = localizedText.text(R.string.ui_sending_paste_images_after_it_finishes_61fac)
            return
        }
        pendingPhotoPastes++
        importingPhotos.value = true
        viewModelScope.launch {
            try {
                photoPasteMutex.withLock {
                    draftReady.await()
                    var failures = 0
                    var overLimit = false
                    for (uri in uris.distinct()) {
                        if (photos.value.size >= CaptureStore.MAX_PHOTOS) {
                            overLimit = true
                            break
                        }
                        try {
                            val path = captureStore.importPastedPhoto(uri)
                            // A returning camera result may have used the last slot while copying.
                            if (photos.value.size < CaptureStore.MAX_PHOTOS) addPhotos(listOf(path))
                            else {
                                captureStore.discard(listOf(path))
                                overLimit = true
                                break
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            failures++
                        }
                    }
                    if (failures > 0 || overLimit) notice.value = listOfNotNull(
                        if (failures > 0) localizedText.text(R.string.ui_could_not_paste_1_s_images_copy_them_again_a6645, failures) else null,
                        if (overLimit) localizedText.text(R.string.ui_up_to_1_s_photos_per_question_extras_were_not_added_a6a5d, CaptureStore.MAX_PHOTOS) else null,
                    ).joinToString("；")
                }
            } finally {
                pendingPhotoPastes--
                importingPhotos.value = pendingPhotoPastes > 0
            }
        }
    }

    /** Use the persisted conversation mode for both prewarming and accepting a crop. */
    suspend fun onPhotoCropStarted(path: String) {
        try {
            photoPreparation.beginCrop(path, currentConversationMode())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Optional prewarming must not prevent opening the crop editor.
        }
    }
    fun onPhotoCropClosed(path: String) = photoPreparation.endCrop(path)
    suspend fun savePhotoCrop(path: String, edit: PhotoCropEdit) = photoPreparation.saveCrop(path, currentConversationMode(), edit)

    /** 裁剪界面「取消」：这张不要了。 */
    fun discardPhoto(path: String) {
        viewModelScope.launch { runCatching { captureStore.discard(listOf(path)) } }
    }

    /** 追问时拍的 / 选的照片：裁剪完成后加入待发列表，超过上限的部分丢弃并提示。 */
    fun addPhotos(paths: List<String>) {
        if (paths.isEmpty()) return
        val room = CaptureStore.MAX_PHOTOS - photos.value.size
        if (room <= 0) {
            notice.value = localizedText.text(R.string.ui_up_to_1_s_photos_per_question_5ed3f, CaptureStore.MAX_PHOTOS)
            return
        }
        if (paths.size > room) notice.value = localizedText.text(R.string.ui_up_to_1_s_photos_per_question_extras_were_not_added_a6a5d, CaptureStore.MAX_PHOTOS)
        val added = paths.take(room)
        PhotoArrivals.markNew(added)
        photos.value = photos.value + added
        saveDraft()
        preparePhotos(added)
    }

    /** Capture ownership before suspension; native work belongs to the application runtime. */
    private fun preparePhotos(paths: List<String>) {
        if (paths.isEmpty()) return
        val owner = conversationId.value
        val newMode = selectedMode.value
        viewModelScope.launch {
            try {
                val mode = owner?.let { conversationRepository.getConversation(it) }
                    ?.let { ConversationMode.fromName(it.conversationMode) } ?: newMode
                photoPreparation.enqueue(paths, mode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Optional presentation work never blocks adding or sending the originals.
            }
        }
    }

    fun removePhoto(path: String) {
        if (switchingBranch.value) return
        if (path !in photos.value) return
        photos.value = photos.value - path
        saveDraft()
    }

    /** 同步 reserve、异步 persist：乱序落盘也只认最新一次编辑（见 DraftStore）。 */
    private fun saveDraft() {
        draftRestored = true
        editRevision++
        val reservation = draftStore.reserveSave(draftSlot, input.value, photos.value, documents.value, selectedMode.value)
        viewModelScope.launch {
            try {
                draftStore.persist(reservation)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice.value = localizedText.text(R.string.ui_could_not_save_draft_send_it_before_leaving_abaf0)
            }
        }
    }

    fun dismissNotice() {
        notice.value = null
    }

    /** 页面层的提示（语音、权限）也走同一条提示行。 */
    fun showNotice(message: String) {
        notice.value = message
    }

    // ---------------- 发送 ----------------

    /** 发送输入框里的内容（文字和 / 或照片）。新题目会先建会话再提交。 */
    fun send() {
        if (!draftLoaded.value || importingPhotos.value || switchingBranch.value) return
        val original = editing.value
        if (original != null && input.value.trim() == original.content.trim() &&
            photos.value == RequestRepository.decodePathList(original.imagePaths) &&
            documents.value == RequestRepository.decodePathList(original.documentPaths)) return
        val text = input.value.trim()
        val pics = photos.value
        if (text.isEmpty() && pics.isEmpty() && documents.value.isEmpty()) return
        submit(text, pics, mode = null, consumesInput = true)
    }

    private fun submit(text: String, attachments: List<String>, mode: SolveMode?, consumesInput: Boolean) {
        if (submitting.value) return
        val active = generationManager.active.value
        if (active.isRunning) {
            notice.value = if (active.conversationId == conversationId.value) {
                localizedText.text(R.string.ui_this_answer_is_generating_wait_or_stop_it_first_6beb2)
            } else {
                localizedText.text(R.string.ui_another_conversation_is_generating_wait_before_sending_efeed)
            }
            return
        }
        // 点击瞬间同步捕获归属与编辑序号：挂起期间的任何变化都不能改变这次发什么、清什么。
        submitting.value = true
        notice.value = null
        val ownerSlot = conversationId.value
        val revisionAtSend = editRevision
        val documentsAtSend = documents.value.toList()
        val slotAtSend = draftSlot
        val editAtSend = editing.value
        val conversationModeAtSend = selectedMode.value
        viewModelScope.launch {
            try {
                submitTurn(text, attachments, mode, ownerSlot, revisionAtSend, consumesInput, documentsAtSend,
                    slotAtSend, editAtSend, conversationModeAtSend)
            } finally {
                submitting.value = false
            }
        }
    }

    private suspend fun submitTurn(
        text: String,
        attachments: List<String>,
        requestedMode: SolveMode?,
        ownerSlot: String?,
        revisionAtSend: Long,
        consumesInput: Boolean,
        documentPaths: List<String>,
        slotAtSend: String?,
        editAtSend: MessageEntity?,
        conversationModeAtSend: ConversationMode,
    ) {
        val mode = requestedMode
            ?: runCatching { settings.current().defaultSolveMode }.getOrDefault(SolveMode.DETAILED)
        // 缺图 / 空图绝不静默降级成纯文本：在建会话之前就拦下，输入框和照片都保留。
        val pinned = try {
            draftStore.persistAttachments(attachments).also {
                documentPaths.forEach { path -> require(File(path).isFile && File(path).length() > 0) { localizedText.text(R.string.ui_original_document_is_missing_a8244) } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice.value = localizedText.text(R.string.ui_some_attachments_cannot_be_read_add_them_again_6661a)
            return
        }
        // 用户可能从历史页删除了当前对话，再返回仍保留草稿的页面。
        // 此时下一次发送建立新对话，避免向已删除的会话写入请求。
        val existingId = try {
            ownerSlot?.takeIf { conversationRepository.getConversation(it) != null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice.value = localizedText.text(R.string.ui_could_not_load_conversation_your_input_is_kept_try_again_0f5c4)
            return
        }
        val created = existingId == null
        val targetId = existingId ?: try {
            // 纯照片题没有文字：先用「图片题目」占位，首次有效回答后由模型概括的主题替换。
            conversationRepository.createConversation(text.ifBlank {
                if (documentPaths.isNotEmpty()) localizedText.text(R.string.ui_document_analysis_7ddd2)
                else if (conversationModeAtSend == ConversationMode.CHAT) localizedText.text(R.string.ui_image_conversation_9bc60) else PHOTO_PLACEHOLDER_TITLE
            }, mode, conversationModeAtSend).id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice.value = localizedText.text(R.string.ui_could_not_save_conversation_1_s_your_input_is_kept_5c45a, e.message ?: localizedText.text(R.string.ui_storage_error_06f62))
            return
        }
        try {
            generationManager.submit(
                GenerationManager.Submission(
                    conversationId = targetId,
                    userText = text,
                    attachmentPaths = pinned,
                    documentPaths = documentPaths,
                    solveMode = mode,
                    conversationMode = conversationModeAtSend,
                    parentMessageId = if (created) null else editAtSend?.parentMessageId ?: if (editAtSend != null) null else ConversationBranches.AUTO_PARENT,
                ),
            )
        } catch (e: GenerationManager.AlreadyRunningException) {
            if (created) discardQuietly(targetId)
            notice.value = localizedText.text(R.string.ui_another_conversation_is_generating_wait_before_sending_efeed)
            return
        } catch (e: ModelException) {
            if (created) discardQuietly(targetId)
            notice.value = e.message ?: localizedText.text(R.string.ui_model_unavailable_check_settings_and_resend_d40bc)
            return
        } catch (e: CancellationException) {
            if (created) withContext(NonCancellable) { discardQuietly(targetId) }
            throw e
        } catch (e: Exception) {
            if (created) discardQuietly(targetId)
            notice.value = localizedText.text(R.string.ui_could_not_save_this_turn_1_s_your_input_is_kept_893de, e.message ?: localizedText.text(R.string.ui_storage_error_06f62))
            return
        }
        if (created) {
            conversationId.value = targetId
            savedState[Routes.ARG_CONVERSATION_ID] = targetId
        }
        // persistAttachments can copy an external original. Queue its durable path too.
        preparePhotos(pinned)
        // 题册封面取首张题目照片；写失败只影响题册缩略图，不影响这一轮解题。
        pinned.firstOrNull()?.let { cover -> runCatching { conversationRepository.setCoverIfEmpty(targetId, cover) } }
        if (consumesInput) consumeDraft(targetId, revisionAtSend, slotAtSend, editAtSend)
    }

    /**
     * 消费「发送时那份输入」。序号没变 ⇒ 清空输入框、待发照片和发送时所在的草稿槽；
     * 序号变了（提交期间又打了字 / 加了图）⇒ 保留新编辑，新题目还要把它从「新题目」槽挪到真实会话槽。
     */
    private suspend fun consumeDraft(targetId: String, revisionAtSend: Long,
                                    slotAtSend: String?, editAtSend: MessageEntity?) {
        // Reserve clearing before suspending; any later keystroke supersedes this reservation.
        val clearReservation = if (editRevision == revisionAtSend) draftStore.reserveSave(slotAtSend, "", emptyList()) else null
        if (editRevision == revisionAtSend) {
            input.value = ""
            photos.value = emptyList()
            documents.value = emptyList()
        }
        try {
            val nextSlot = normalDraftSlot(targetId)
            draftSlot = nextSlot
            val unchanged = editRevision == revisionAtSend
            val moveReservation = if (!unchanged && slotAtSend != nextSlot)
                draftStore.reserveSave(nextSlot, input.value, photos.value, documents.value) else null
            val oldSlotReservation = if (!unchanged && slotAtSend != nextSlot) draftStore.reserveSave(slotAtSend, "", emptyList()) else clearReservation
            if (editAtSend != null) {
                editing.value = null
                draftStore.clear(editMetaSlot(targetId))
                // The pre-edit ordinary draft was parked under its old branch tip.
                if (nextSlot != targetId) draftStore.clear(targetId)
            }
            moveReservation?.let { draftStore.persist(it) }
            oldSlotReservation?.let { draftStore.persist(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice.value = localizedText.text(R.string.ui_draft_could_not_be_removed_and_may_reappear_next_time_ecfaf)
        }
    }

    private suspend fun discardQuietly(id: String) {
        runCatching { conversationRepository.discardIfEmpty(id) }
    }

    // ---------------- 停止 ----------------

    /** 停止本题正在进行的生成：真正取消网络请求，不自动重发。 */
    fun stop() {
        val active = generationManager.active.value
        val requestId = active.requestId ?: return
        if (!active.isRunning || active.conversationId != conversationId.value) return
        viewModelScope.launch {
            // 带上 attempt：旧 attempt 的迟到取消不会掐掉已经换了 attempt 的新一轮。
            generationManager.cancel(requestId, active.attemptId)
        }
    }

    // ---------------- 重新发送 ----------------
    /**
     * 中断解答纸上的「重新发送」。
     *
     * 复用**原用户消息与回答位置**，只换 attemptId：最终只有一条提问、一个回答位置。
     * 按**当前**服务商重新捕获快照（用户可能刚在设置里换了模型），原附件重新走看图路线。
     */
    fun retry(requestId: String) = recoverInterrupted(requestId, continueAnswer = false)

    fun resume(requestId: String) = recoverInterrupted(requestId, continueAnswer = true)

    private fun recoverInterrupted(requestId: String, continueAnswer: Boolean) {
        if (submitting.value) return
        if (generationManager.active.value.isRunning) {
            notice.value = localizedText.text(R.string.ui_another_conversation_is_generating_resend_later_8d404)
            return
        }
        submitting.value = true
        notice.value = null
        val ownerConversation = conversationId.value
        viewModelScope.launch {
            // beginRetry 提交后、交接前若被取消，回滚必须知道新 attempt：先记下再落库。
            var newAttempt: String? = null
            try {
                val existing = requestRepository.get(requestId)
                if (existing == null || existing.conversationId != ownerConversation) {
                    notice.value = localizedText.text(R.string.ui_this_record_no_longer_exists_eef88)
                    return@launch
                }
                if (RequestStatus.fromName(existing.status) != RequestStatus.INTERRUPTED) {
                    return@launch // 已被别处重发或已完成：列表会随 Room 自己刷新
                }
                if (continueAnswer && existing.partialText.isBlank()) {
                    notice.value = localizedText.text(R.string.ui_no_answer_to_continue_choose_resend_802b8)
                    return@launch
                }
                // 用户已经追问过：这条中断保留为历史记录，不插队重放。
                val latestUser = conversationRepository.visibleMessages(existing.conversationId)
                    .lastOrNull { it.role != ROLE_ASSISTANT }?.id
                if (latestUser != null && latestUser != existing.userMessageId) {
                    notice.value = localizedText.text(R.string.ui_a_newer_question_was_sent_this_interruption_is_kept_in_histo_e19d5)
                    return@launch
                }
                // 缺图绝不静默降级成纯文本：明确提示，不发网络。
                val attachments = RequestRepository.decodePathList(existing.attachmentPaths)
                val missing = attachments.count { !File(it).isFile }
                if (missing > 0) {
                    notice.value = localizedText.text(R.string.ui_1_s_original_images_are_missing_add_them_again_to_resend_637a0, missing)
                    return@launch
                }
                val snapshot = generationManager.captureRetrySnapshot(existing, attachments).copy(
                    continuationText = if (continueAnswer) existing.partialText else "",
                )
                val attempt = UUID.randomUUID().toString()
                newAttempt = attempt
                // 原子 CAS：并发/重复点只有一个能抢到；已不是 INTERRUPTED 时返回 null。
                requestRepository.beginRetry(requestId, attempt, SnapshotCodec.encode(snapshot), attachments,
                    expectedAttemptId = existing.attemptId)
                    ?: run {
                        newAttempt = null
                        return@launch
                    }
                generationManager.submitRetry(requestId, attempt)
            } catch (e: GenerationManager.AlreadyRunningException) {
                notice.value = localizedText.text(R.string.ui_another_conversation_is_generating_resend_later_8d404)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: if (continueAnswer) localizedText.text(R.string.ui_could_not_continue_a6e8b) else localizedText.text(R.string.ui_could_not_resend_ad9dd)
            } finally {
                rollbackRejectedRetry(requestId, newAttempt)
                submitting.value = false
            }
        }
    }

    // ---------------- 重新生成 ----------------

    /**
     * 最后一张解答纸的「重新生成」：同一个提问、同一个回答位置，换新 attempt 再答一遍。
     *
     * 与 [retry] 同构：按**当前**服务商重新捕获快照，原附件重新走看图路线；
     * 交接失败时回滚成可重试的中断（旧正文保留在请求记录里，界面仍看得到）。
     */
    fun regenerate(requestId: String) {
        if (submitting.value) return
        if (generationManager.active.value.isRunning) {
            notice.value = localizedText.text(R.string.ui_another_conversation_is_generating_regenerate_later_caa91)
            return
        }
        submitting.value = true
        notice.value = null
        val ownerConversation = conversationId.value
        viewModelScope.launch {
            var newAttempt: String? = null
            try {
                val existing = requestRepository.get(requestId)
                if (existing == null || existing.conversationId != ownerConversation) {
                    notice.value = localizedText.text(R.string.ui_this_record_no_longer_exists_eef88)
                    return@launch
                }
                if (RequestStatus.fromName(existing.status)?.isInFlight != false) return@launch
                val latestUser = conversationRepository.visibleMessages(existing.conversationId)
                    .lastOrNull { it.role != ROLE_ASSISTANT }?.id
                if (latestUser != null && latestUser != existing.userMessageId) {
                    notice.value = localizedText.text(R.string.ui_a_newer_question_was_sent_only_the_last_answer_can_be_regene_98653)
                    return@launch
                }
                val attachments = RequestRepository.decodePathList(existing.attachmentPaths)
                val missing = attachments.count { !File(it).isFile }
                if (missing > 0) {
                    notice.value = localizedText.text(R.string.ui_1_s_original_images_are_missing_add_them_again_to_regenerate_9a6d8, missing)
                    return@launch
                }
                val snapshot = generationManager.captureRetrySnapshot(existing, attachments)
                val attempt = UUID.randomUUID().toString()
                newAttempt = attempt
                requestRepository.beginRegenerate(requestId, existing.attemptId, attempt, SnapshotCodec.encode(snapshot))
                    ?: run {
                        newAttempt = null
                        return@launch
                    }
                generationManager.submitRetry(requestId, attempt)
            } catch (e: GenerationManager.AlreadyRunningException) {
                notice.value = localizedText.text(R.string.ui_another_conversation_is_generating_regenerate_later_caa91)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: localizedText.text(R.string.ui_could_not_regenerate_49963)
            } finally {
                rollbackRejectedRetry(requestId, newAttempt)
                submitting.value = false
            }
        }
    }

    /**
     * 交接失败的回滚：`beginRetry` 已把记录改成 PREPARING，但管理器**当前**没有在跑这条
     * request + attempt ⇒ 立即收成可重试的中断，否则记录永远卡在 PREPARING，
     * 既不能重发也要等下次启动扫描才救得回来。
     *
     * 读的是**当前**管理器身份：submitRetry 已经登记好、协程才被取消的情况下，
     * 这一轮是活的，回滚绝不能掐断它。整段 NonCancellable：调用方常处于取消中。
     */
    private suspend fun rollbackRejectedRetry(requestId: String, attemptId: String?) {
        attemptId ?: return
        withContext(NonCancellable) {
            runCatching {
                val record = requestRepository.get(requestId) ?: return@runCatching
                if (record.attemptId != attemptId) return@runCatching
                if (RequestStatus.fromName(record.status)?.isInFlight != true) return@runCatching
                val active = generationManager.active.value
                if (active.requestId == requestId && active.attemptId == attemptId) return@runCatching
                requestRepository.interrupt(
                    requestId = requestId,
                    attemptId = attemptId,
                    partialText = record.partialText,
                    kind = FailureKind.NETWORK,
                    message = localizedText.text(R.string.ui_resend_did_not_start_tap_resend_again_85f49),
                )
            }
        }
    }
}
