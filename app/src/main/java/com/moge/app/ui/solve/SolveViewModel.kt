package com.moge.app.ui.solve

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.moge.app.data.db.ConversationEntity
import com.moge.app.data.db.ConversationRepository
import com.moge.app.data.db.ConversationRepository.Companion.PHOTO_PLACEHOLDER_TITLE
import com.moge.app.data.db.MessageEntity
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.llm.SnapshotCodec
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.FailureKind
import com.moge.app.domain.RequestStatus
import com.moge.app.domain.SolveMode
import com.moge.app.runtime.DraftStore
import com.moge.app.runtime.GenerationManager
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
    val documentPaths: List<String> = emptyList(),
    val answerFirst: Boolean = false,
) {
    val canSend: Boolean
        get() = (input.isNotBlank() || photos.isNotEmpty() || documentPaths.isNotEmpty()) && !generating && !busyElsewhere && !submitting

    val canAddPhoto: Boolean
        get() = photos.size < CaptureStore.MAX_PHOTOS && !submitting
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
) : ViewModel() {

    /** 当前会话；null = 还没发出第一问的新题目。建好后写回 [savedState]，旋转/重建后仍指向它。 */
    private val conversationId = MutableStateFlow(
        savedState.get<String>(Routes.ARG_CONVERSATION_ID)?.takeIf { it.isNotBlank() },
    )

    private val input = MutableStateFlow("")
    private val photos = MutableStateFlow<List<String>>(emptyList())
    private val documents = MutableStateFlow<List<String>>(emptyList())
    private val submitting = MutableStateFlow(false)
    private val notice = MutableStateFlow<String?>(null)

    /** 输入框（文字 + 待发照片）的编辑序号：每次改动 +1。发送成功后只有序号没变才清空输入与草稿。 */
    private var editRevision = 0L

    /** 用户在草稿恢复完成前就开始打字：恢复结果不得覆盖新输入。 */
    private var draftRestored = false
    private val draftReady = CompletableDeferred<Unit>()

    private data class RoomSnapshot(
        val conversation: ConversationEntity?,
        val messages: List<MessageEntity>,
        val requests: List<RequestEntity>,
    )

    private val roomState: Flow<RoomSnapshot> = conversationId.flatMapLatest { id ->
        if (id == null) {
            flowOf(RoomSnapshot(null, emptyList(), emptyList()))
        } else {
            combine(
                conversationRepository.observeConversation(id),
                conversationRepository.observeMessages(id),
                requestRepository.observeForConversation(id),
            ) { conversation, messages, requests -> RoomSnapshot(conversation, messages, requests) }
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
            val generatingHere = active.isRunning && id != null && active.conversationId == id
            SolveUiState(
                conversationId = id,
                title = room.conversation?.title ?: ConversationRepository.PLACEHOLDER_TITLE,
                items = buildSolveItems(id, room.messages, room.requests, active),
                input = local.input,
                generating = generatingHere,
                busyElsewhere = active.isRunning && !generatingHere,
                submitting = local.submitting,
                notice = local.notice,
                photos = local.photos,
                documentPaths = local.documents,
                answerFirst = prefs.answerFirst,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SolveUiState(conversationId.value))

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

    private fun restoreDraft() {
        val key = conversationId.value
        viewModelScope.launch {
            val draft = try {
                draftStore.load(key)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // 草稿读不出来不影响解题，空输入框即可
            }
            if (!draftRestored && conversationId.value == key && draft != null) {
                input.value = draft.text
                // 缺图引用照样恢复：发送时 persistAttachments 会明确报错，而不是悄悄丢图。
                photos.value = draft.photoPaths.take(CaptureStore.MAX_PHOTOS)
                documents.value = draft.documentPaths
            }
            draftRestored = true
            draftReady.complete(Unit)
        }
    }

    fun onInputChange(text: String) {
        input.value = text
        saveDraft()
    }

    /** Import originals as document attachments; never modify the question text. */
    fun importDocument(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                val path = captureStore.importDocument(uri)
                importDocuments(listOf(path))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: "文件导入失败"
            }
        }
    }

    private suspend fun importDocuments(paths: List<String>) {
        if (paths.isEmpty()) return
        documentImportMutex.withLock {
            val remaining = com.moge.app.data.document.DocumentStore.MAX_ATTACHMENTS - documents.value.size
            documents.value = (documents.value + paths.take(remaining.coerceAtLeast(0))).distinct()
            saveDraft()
            if (paths.size > remaining) notice.value = "每次最多添加 8 个文档"
        }
    }

    fun removeDocument(path: String) {
        documents.value = documents.value.filterNot { it == path }
        saveDraft()
    }

    /** 追问拍照的目标文件（落在私有 photos 目录，裁剪原地改写）。 */
    fun newCameraFile(): File = captureStore.newCameraFile()

    /** 追问时从相册选的图：复制进私有目录后交给页面逐张裁剪。 */
    fun importPicked(uris: List<android.net.Uri>, onImported: (List<String>) -> Unit) {
        if (uris.isEmpty()) return
        val room = CaptureStore.MAX_PHOTOS - photos.value.size
        if (room <= 0) {
            notice.value = "一道题最多 ${CaptureStore.MAX_PHOTOS} 张照片"
            return
        }
        viewModelScope.launch {
            try {
                onImported(captureStore.importFromGallery(uris.take(room)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: "照片导入失败"
            }
        }
    }

    /** 裁剪界面「取消」：这张不要了。 */
    fun discardPhoto(path: String) {
        viewModelScope.launch { runCatching { captureStore.discard(listOf(path)) } }
    }

    /** 追问时拍的 / 选的照片：裁剪完成后加入待发列表，超过上限的部分丢弃并提示。 */
    fun addPhotos(paths: List<String>) {
        if (paths.isEmpty()) return
        val room = CaptureStore.MAX_PHOTOS - photos.value.size
        if (room <= 0) {
            notice.value = "一道题最多 ${CaptureStore.MAX_PHOTOS} 张照片"
            return
        }
        if (paths.size > room) notice.value = "一道题最多 ${CaptureStore.MAX_PHOTOS} 张照片，多出的没有加入"
        val added = paths.take(room)
        PhotoArrivals.markNew(added)
        photos.value = photos.value + added
        saveDraft()
    }

    fun removePhoto(path: String) {
        if (path !in photos.value) return
        photos.value = photos.value - path
        saveDraft()
    }

    /** 同步 reserve、异步 persist：乱序落盘也只认最新一次编辑（见 DraftStore）。 */
    private fun saveDraft() {
        draftRestored = true
        editRevision++
        val reservation = draftStore.reserveSave(conversationId.value, input.value, photos.value, documents.value)
        viewModelScope.launch {
            try {
                draftStore.persist(reservation)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                notice.value = "草稿没能保存到本地，退出前请先发送"
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
                "这一题还在生成，等它结束或先停止"
            } else {
                "另一道题正在生成回答，请等它结束后再发送"
            }
            return
        }
        // 点击瞬间同步捕获归属与编辑序号：挂起期间的任何变化都不能改变这次发什么、清什么。
        submitting.value = true
        notice.value = null
        val ownerSlot = conversationId.value
        val revisionAtSend = editRevision
        val documentsAtSend = documents.value.toList()
        viewModelScope.launch {
            try {
                submitTurn(text, attachments, mode, ownerSlot, revisionAtSend, consumesInput, documentsAtSend)
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
    ) {
        val mode = requestedMode
            ?: runCatching { settings.current().defaultSolveMode }.getOrDefault(SolveMode.DETAILED)
        // 缺图 / 空图绝不静默降级成纯文本：在建会话之前就拦下，输入框和照片都保留。
        val pinned = try {
            draftStore.persistAttachments(attachments).also {
                documentPaths.forEach { path -> require(File(path).isFile && File(path).length() > 0) { "原文档已不存在" } }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice.value = "有附件读不出来了，请重新添加后发送"
            return
        }
        // 用户可能从历史页删除了当前对话，再返回仍保留草稿的页面。
        // 此时下一次发送建立新对话，避免向已删除的会话写入请求。
        val existingId = try {
            ownerSlot?.takeIf { conversationRepository.getConversation(it) != null }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice.value = "对话没能读取，内容已保留在输入框，请重试"
            return
        }
        val created = existingId == null
        val targetId = existingId ?: try {
            // 纯照片题没有文字：先用「图片题目」占位，首次有效回答后由模型概括的主题替换。
            conversationRepository.createConversation(text.ifBlank { documentPaths.firstOrNull()?.let { "文档分析" } ?: PHOTO_PLACEHOLDER_TITLE }, mode).id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notice.value = "这道题没能保存到本地（${e.message ?: "存储异常"}），内容已保留在输入框"
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
                ),
            )
        } catch (e: GenerationManager.AlreadyRunningException) {
            if (created) discardQuietly(targetId)
            notice.value = "另一道题正在生成回答，请等它结束后再发送"
            return
        } catch (e: CancellationException) {
            if (created) withContext(NonCancellable) { discardQuietly(targetId) }
            throw e
        } catch (e: Exception) {
            if (created) discardQuietly(targetId)
            notice.value = "这一轮没能保存到本地（${e.message ?: "存储异常"}），内容已保留在输入框"
            return
        }
        if (created) {
            conversationId.value = targetId
            savedState[Routes.ARG_CONVERSATION_ID] = targetId
        }
        // 题册封面取首张题目照片；写失败只影响题册缩略图，不影响这一轮解题。
        pinned.firstOrNull()?.let { cover -> runCatching { conversationRepository.setCoverIfEmpty(targetId, cover) } }
        if (consumesInput) consumeDraft(ownerSlot, targetId, revisionAtSend)
    }

    /**
     * 消费「发送时那份输入」。序号没变 ⇒ 清空输入框、待发照片和发送时所在的草稿槽；
     * 序号变了（提交期间又打了字 / 加了图）⇒ 保留新编辑，新题目还要把它从「新题目」槽挪到真实会话槽。
     */
    private suspend fun consumeDraft(ownerSlot: String?, targetId: String, revisionAtSend: Long) {
        val unchanged = editRevision == revisionAtSend
        if (unchanged) {
            input.value = ""
            photos.value = emptyList()
            documents.value = emptyList()
        }
        try {
            if (!unchanged && ownerSlot != targetId) {
                draftStore.persist(draftStore.reserveSave(targetId, input.value, photos.value, documents.value))
            }
            if (unchanged || ownerSlot != targetId) {
                draftStore.persist(draftStore.reserveSave(ownerSlot, "", emptyList()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            notice.value = "草稿没能清理，下次打开时可能还会出现"
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
            notice.value = "另一道题正在生成回答，请稍后再重发"
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
                    notice.value = "这条记录已经不在了"
                    return@launch
                }
                if (RequestStatus.fromName(existing.status) != RequestStatus.INTERRUPTED) {
                    return@launch // 已被别处重发或已完成：列表会随 Room 自己刷新
                }
                if (continueAnswer && existing.partialText.isBlank()) {
                    notice.value = "还没有可接续的回答，请选择重新发送"
                    return@launch
                }
                // 用户已经追问过：这条中断保留为历史记录，不插队重放。
                val latestUser = conversationRepository.messages(existing.conversationId)
                    .lastOrNull { it.role != ROLE_ASSISTANT }?.id
                if (latestUser != null && latestUser != existing.userMessageId) {
                    notice.value = "你已经发过新的问题了，这条中断已保留为历史记录"
                    return@launch
                }
                // 缺图绝不静默降级成纯文本：明确提示，不发网络。
                val attachments = RequestRepository.decodePathList(existing.attachmentPaths)
                val missing = attachments.count { !File(it).isFile }
                if (missing > 0) {
                    notice.value = "原题目里有 $missing 张图片已被清理，无法重发，请重新拍题"
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
                notice.value = "另一道题正在生成回答，请稍后再重发"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: if (continueAnswer) "接续失败" else "重发失败"
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
            notice.value = "另一道题正在生成回答，请稍后再重新生成"
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
                    notice.value = "这条记录已经不在了"
                    return@launch
                }
                if (RequestStatus.fromName(existing.status)?.isInFlight != false) return@launch
                val latestUser = conversationRepository.messages(existing.conversationId)
                    .lastOrNull { it.role != ROLE_ASSISTANT }?.id
                if (latestUser != null && latestUser != existing.userMessageId) {
                    notice.value = "你已经发过新的问题了，只能重新生成最后一个回答"
                    return@launch
                }
                val attachments = RequestRepository.decodePathList(existing.attachmentPaths)
                val missing = attachments.count { !File(it).isFile }
                if (missing > 0) {
                    notice.value = "原题目里有 $missing 张图片已被清理，无法重新生成，请重新拍题"
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
                notice.value = "另一道题正在生成回答，请稍后再重新生成"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notice.value = e.message ?: "重新生成失败"
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
                    message = "重发没能开始，可以再点一次重新发送",
                )
            }
        }
    }
}
