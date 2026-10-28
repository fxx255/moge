package com.moge.app.runtime

import com.moge.app.data.credential.AiCredentialStore
import com.moge.app.data.credential.AiReasoningEffort
import com.moge.app.data.credential.AiSearchProtocol
import com.moge.app.data.db.ConversationDao
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.db.ConversationBranches
import com.moge.app.data.llm.ChatMessage
import com.moge.app.data.llm.AnswerUsage
import com.moge.app.data.llm.HISTORY_MAX_MESSAGES
import com.moge.app.data.llm.PromptAssembler
import com.moge.app.data.llm.VisionInputImageStore
import com.moge.app.data.llm.ModelClient
import com.moge.app.data.llm.ModelException
import com.moge.app.data.llm.RequestPolicy
import com.moge.app.data.llm.RequestSnapshot
import com.moge.app.data.llm.SnapshotCodec
import com.moge.app.data.llm.endpointIdentityOf
import com.moge.app.data.llm.shouldUseWebSearch
import com.moge.app.data.parse.replaceFigureAnchors
import com.moge.app.data.prefs.SettingsRepository
import com.moge.app.domain.SolveMode
import com.moge.app.domain.ConversationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val HISTORY_IMAGE_LOOKBACK_MESSAGES = 8
private const val HISTORY_IMAGE_LIMIT = 2

/**
 * 生成前的**准备阶段**：转写照片、组历史、定联网。
 *
 * 照片转写是网络调用，可能跑好几秒；放在 ViewModel 里会被切页/旋转/锁屏一并取消，
 * 记录卡在 PREPARING。整段准备归应用级管理器，与页面生命周期无关。
 *
 * 配置错误用 [ModelException] 交给提交入口提示；附件失败以 [Prepared.failure] 返回。
 * 已创建的请求由管理器统一落成带原因的终态。
 */
@Singleton
class GenerationPreparer @Inject constructor(
    private val modelClient: ModelClient,
    private val conversationDao: ConversationDao,
    private val settings: SettingsRepository,
    private val credentialStore: AiCredentialStore,
    private val requestRepository: RequestRepository,
    private val visionImages: VisionInputImageStore,
) {
    /** 准备结果：[failure] 非空表示这一轮无法开始，调用方须落终态。 */
    data class Prepared(
        val outgoingText: String,
        /** 历史 + 当前轮（当前轮必须是最后一条）。 */
        val history: List<ChatMessage>,
        val imageBase64s: List<String>,
        val solveMode: SolveMode,
        val webSearchEnabled: Boolean,
        val forceWebSearch: Boolean,
        val policy: RequestPolicy,
        /** 补写了准备结果的快照；主请求发出前必须先落库。 */
        val snapshot: RequestSnapshot,
        /** 转写路线的识别结果（不含用户补充说明），存到用户消息上供展示。 */
        val transcript: String? = null,
        val failure: String? = null,
    ) {
        val usageGroupKey: String get() = snapshot.usageGroupKey
    }

    /**
     * 新提问的准备：**全部以 [captureInitialSnapshot] 钉下的 [initialSnapshot] 为准**，
     * 不重读活动档案、不重建历史，只做网络工作（转写）并把结果补写进快照。
     */
    suspend fun prepare(
        submission: GenerationManager.Submission,
        owner: RequestEntity,
        initialSnapshot: RequestSnapshot,
    ): Prepared {
        validateConfiguration(initialSnapshot, submission.attachmentPaths.isNotEmpty())
        if (!initialSnapshot.isComplete) {
            return failure(owner, "本轮恢复信息不完整，无法安全准备；请重新发送本次消息")
        }
        val initial = refreshCapturedPrompt(initialSnapshot)
        val photo = when (initial.photoRoute) {
            RequestSnapshot.PHOTO_ROUTE_NONE -> PhotoOutcome(emptyList(), submission.userText)
            RequestSnapshot.PHOTO_ROUTE_DIRECT -> {
                val encoded = encodeAll(submission.attachmentPaths)
                if (encoded.failure != null) return failure(owner, encoded.failure)
                // Preserve explicit instructions; captionless follow-ups must not restart the whole solution.
                encoded.copy(outgoingText = submission.userText.ifBlank { defaultImagePrompt(initial) })
            }
            RequestSnapshot.PHOTO_ROUTE_TRANSCRIBE ->
                transcribe(submission.attachmentPaths, submission.userText, initial)
            else -> return failure(owner, "这一轮的照片路由无法识别，请重新发送")
        }
        if (photo.failure != null) return failure(owner, photo.failure)
        val outgoing = photo.outgoingText
        // 历史来自快照，绝不重读当前会话。
        val history = historyFromSnapshot(initial) + ChatMessage("user", outgoing, documentPaths = initial.documentPaths,
            documentReadRequired = initial.documentReadRequired, turnContext = initial.prompt?.volatileSystem.orEmpty())
        val policy = initial.toPolicy()
        return Prepared(
            outgoingText = outgoing,
            history = history,
            imageBase64s = photo.imageBase64s,
            solveMode = SolveMode.fromName(initial.solveMode) ?: SolveMode.DETAILED,
            webSearchEnabled = shouldUseWebSearch(outgoing, initial.forceWebSearch) &&
                policy.effectiveWebSearchEnabled,
            forceWebSearch = initial.forceWebSearch,
            policy = policy,
            snapshot = initial.copy(
                prepared = true,
                sourceUserText = outgoing,
                historyMessages = history.size,
                hasImages = photo.imageBase64s.isNotEmpty(),
                preparationUsage = photo.usage,
            ),
            transcript = photo.transcript,
        )
    }

    /** 本地配置检查：必须先于前台保活启动和输入草稿消费。 */
    internal fun validateConfiguration(initial: RequestSnapshot, hasAttachments: Boolean) {
        if (initial.model.isBlank() && initial.primaryProfileId.isBlank()) {
            // 提交时就没有可用档案：引导去设置页，不报成「恢复信息不完整」。
            throw ModelException(
                ModelException.Kind.NOT_CONFIGURED,
                "还没有可用的模型：请在设置页添加接口地址、模型名与 API 密钥",
            )
        }
        // 有附件却既不能看图、也没有识题模型 ⇒ 明确失败（配置失效），
        // 绝不静默降级成纯文本提问让模型凭一句「看图」瞎编。
        if (hasAttachments && initial.photoRoute == RequestSnapshot.PHOTO_ROUTE_NONE) {
            throw ModelException(
                ModelException.Kind.CONFIG_INVALID,
                if (ConversationMode.fromName(initial.conversationMode) == ConversationMode.CHAT)
                    "当前模型不支持图片，请在设置中切换到支持看图的模型后重新发送"
                else "当前模型不支持图片，请在设置中切换到支持看图的模型，或配置识题模型后重新发送",
            )
        }
    }

    /**
     * **网络之前**钉下的初始快照：原问题、原历史（有界）、照片路线、主/识题档案身份、
     * 推理与联网设置、解题模式。随 `createRequest` 一起落库，
     * 这样「准备阶段被打断」也有东西可重试。**不含**密钥与图片 base64。
     */
    suspend fun captureInitialSnapshot(
        submission: GenerationManager.Submission,
        conversationId: String,
        userMessageId: String,
        answerMessageId: String,
    ): RequestSnapshot {
        val prefs = settings.current()
        val conversation = conversationDao.getConversation(conversationId)
            ?: throw ModelException(ModelException.Kind.CONFIG_INVALID, "这个对话已不存在，内容已保留，请重新发送")
        val savedMode = ConversationMode.fromName(conversation.conversationMode)
        val conversationMode = submission.conversationMode ?: savedMode
        if (conversationMode != savedMode) {
            throw ModelException(ModelException.Kind.CONFIG_INVALID,
                "会话模式与发送模式不一致，请返回后重新打开对话再发送")
        }
        val identity = credentialStore.resolveActiveIdentity()
        val baseUrl = identity?.baseUrl.orEmpty()
        // 历史在此刻钉下：之后用户删消息都不能改变这轮的上下文。
        val allMessages = conversationDao.getMessages(conversationId)
        val sourceHistory = if (allMessages.any { it.id == userMessageId })
            ConversationBranches.before(allMessages, userMessageId)
        else if (submission.parentMessageId == ConversationBranches.AUTO_PARENT)
            ConversationBranches.visible(allMessages, conversationDao.getBranchSelections(conversationId))
        else ConversationBranches.ancestors(allMessages, submission.parentMessageId)
        val eligible = sourceHistory.filterNot { it.id == userMessageId || it.id == answerMessageId }
            .takeLast(HISTORY_MAX_MESSAGES)
        val bounded = ModelHistorySnapshot.capture(eligible,
            requestRepository.forMessages(conversationId, eligible.map { it.id }), conversationMode)
        val visionDirect = identity?.visionEnabled ?: false
        val recognizer = if (visionDirect || conversationMode == ConversationMode.CHAT) null else credentialStore.questionVisionProfileId()
            ?.let { credentialStore.resolveIdentityFor(it) }
        // 「有图片但两条路都不通」由 hasImages=true + photoRoute=none 携带，提交前据此拦截。
        val route = when {
            submission.attachmentPaths.isEmpty() -> RequestSnapshot.PHOTO_ROUTE_NONE
            visionDirect -> RequestSnapshot.PHOTO_ROUTE_DIRECT
            recognizer != null -> RequestSnapshot.PHOTO_ROUTE_TRANSCRIBE
            else -> RequestSnapshot.PHOTO_ROUTE_NONE
        }
        return RequestSnapshot(
            model = identity?.model.orEmpty(),
            endpointIdentity = endpointIdentityOf(baseUrl),
            protocol = protocolOf(identity?.searchProtocol),
            apiProtocol = identity?.apiProtocol?.name.orEmpty(),
            documentReadRequired = submission.documentPaths.isNotEmpty(),
            documentPaths = (sourceHistory.filter { it.role == "user" }
                .flatMap { RequestRepository.decodePathList(it.documentPaths) } + submission.documentPaths).distinct(),
            reasoningEffort = (identity?.reasoningEffort ?: AiReasoningEffort.LOW).name,
            solveMode = submission.solveMode.name,
            conversationMode = conversationMode.name,
            hasImages = submission.attachmentPaths.isNotEmpty(),
            sourceUserText = submission.userText,
            prepared = false,
            forceWebSearch = submission.forceWebSearch,
            effectiveWebSearchEnabled = prefs.webSearchEnabled && (identity?.searchEnabled != false),
            primaryProfileId = identity?.profileId.orEmpty(),
            primaryVisionEnabled = visionDirect,
            visionProfileId = recognizer?.profileId.orEmpty(),
            visionModel = recognizer?.model.orEmpty(),
            visionEndpointIdentity = recognizer?.let { endpointIdentityOf(it.baseUrl) }.orEmpty(),
            photoRoute = route,
            originalHistory = bounded,
            prompt = PromptAssembler.assemble(ModelClient.SYSTEM_PROMPT, prefs.nickname,
                identity?.reasoningEffort ?: AiReasoningEffort.LOW, submission.solveMode,
                conversationMode = conversationMode, answerLanguage = prefs.answerLanguage),
            modelHistoryVersion = 1,
            autoResumeOnNetworkLoss = prefs.autoResumeOnNetworkLoss,
        )
    }

    /**
     * 重试的准备：**快照是权威**。
     *
     * - `prepared = false`（准备被打断）⇒ 按原路线、原附件、原识题档案重跑准备；
     * - `prepared = true` ⇒ 用快照里的转写文本直接进入生成；直送路线必须把原图重新编码，
     *   数量不符即失败，绝不静默降级成无图提问。
     */
    suspend fun prepareRetry(owner: RequestEntity): Prepared {
        val captured = SnapshotCodec.decode(owner.snapshotJson)
        when {
            captured == null -> return failure(owner, "这一轮没有恢复信息，无法安全重发。请重新发送本次消息。")
            !captured.isComplete -> return failure(owner, "这一轮的恢复信息不完整，无法安全重发。请重新发送本次消息。")
        }
        val attachments = RequestRepository.decodePathList(owner.attachmentPaths)
        validateConfiguration(captured, attachments.isNotEmpty())
        val snapshot = refreshCapturedPrompt(captured)
        if (snapshot.prepared) return preparedRetryFromSnapshot(snapshot, owner, attachments)
        val submission = GenerationManager.Submission(
            conversationId = owner.conversationId,
            userText = snapshot.sourceUserText,
            attachmentPaths = attachments,
            documentPaths = RequestRepository.decodePathList(owner.documentPaths),
            solveMode = SolveMode.fromName(snapshot.solveMode) ?: SolveMode.DETAILED,
            forceWebSearch = snapshot.forceWebSearch,
        )
        return prepare(submission, owner, snapshot)
    }

    /** Persist the repaired prompt before HTTP so later history replays what was actually sent. */
    private suspend fun refreshCapturedPrompt(snapshot: RequestSnapshot): RequestSnapshot {
        if (snapshot.prompt == null) return snapshot // Legacy snapshots retain the client's configuration fallback.
        val mode = ConversationMode.fromName(snapshot.conversationMode)
        val solveMode = SolveMode.fromName(snapshot.solveMode) ?: SolveMode.DETAILED
        if (PromptAssembler.reusablePrompt(snapshot.prompt, solveMode, mode) != null) return snapshot
        val effort = AiReasoningEffort.entries.firstOrNull { it.name == snapshot.reasoningEffort }
            ?: (if (snapshot.primaryProfileId.isBlank()) credentialStore.resolveActiveIdentity()
                else credentialStore.resolveIdentityFor(snapshot.primaryProfileId))?.reasoningEffort
            ?: AiReasoningEffort.LOW
        return snapshot.copy(prompt = PromptAssembler.assemble(ModelClient.SYSTEM_PROMPT,
            settings.current().nickname, effort, solveMode, conversationMode = mode,
            answerLanguage = snapshot.prompt.answerLanguage))
    }

    private suspend fun preparedRetryFromSnapshot(
        snapshot: RequestSnapshot,
        owner: RequestEntity,
        attachments: List<String>,
    ): Prepared {
        val outgoing = snapshot.sourceUserText
        val history = historyFromSnapshot(snapshot) + ChatMessage("user", outgoing, documentPaths = snapshot.documentPaths,
            documentReadRequired = snapshot.documentReadRequired, turnContext = snapshot.prompt?.volatileSystem.orEmpty())
        val imageBase64s = when (snapshot.photoRoute) {
            RequestSnapshot.PHOTO_ROUTE_DIRECT -> {
                if (attachments.isEmpty()) {
                    return failure(owner, "原附件路径没有随本轮记录保存，无法带上原图重试；请重新发送本次消息")
                }
                val encoded = encodeAll(attachments)
                if (encoded.failure != null) return failure(owner, encoded.failure)
                encoded.imageBase64s
            }
            // 转写路线：准备已完成 ⇒ 用保存的转写文本，主模型 0 张图。
            else -> emptyList()
        }
        val policy = snapshot.toPolicy()
        return Prepared(
            outgoingText = outgoing,
            history = history,
            imageBase64s = imageBase64s,
            solveMode = SolveMode.fromName(snapshot.solveMode) ?: SolveMode.DETAILED,
            webSearchEnabled = shouldUseWebSearch(outgoing, snapshot.forceWebSearch) &&
                policy.effectiveWebSearchEnabled,
            forceWebSearch = snapshot.forceWebSearch,
            policy = policy,
            snapshot = snapshot,
        )
    }

    private fun failure(owner: RequestEntity, message: String) = Prepared(
        outgoingText = owner.userText,
        history = emptyList(),
        imageBase64s = emptyList(),
        solveMode = SolveMode.DETAILED,
        webSearchEnabled = false,
        forceWebSearch = false,
        policy = RequestPolicy(),
        snapshot = RequestSnapshot(),
        failure = message,
    )

    private data class PhotoOutcome(
        val imageBase64s: List<String>,
        val outgoingText: String,
        val failure: String? = null,
        val transcript: String? = null,
        val usage: AnswerUsage? = null,
    )

    /**
     * 用**快照钉下的识题档案**（id + 模型 + 端点身份）转写原附件。
     * 档案被删/换模型/换端点 ⇒ 明确失败，绝不静默换一个识题模型；取消原样传播。
     */
    private suspend fun transcribe(
        photoPaths: List<String>,
        prompt: String,
        snapshot: RequestSnapshot,
    ): PhotoOutcome {
        val profileId = snapshot.visionProfileId
        if (profileId.isBlank()) {
            return PhotoOutcome(emptyList(), prompt, failure = "本轮未记录题目识别模型，无法转写原图片；请重新发送")
        }
        val vision = credentialStore.resolveIdentityFor(profileId)
            ?: return PhotoOutcome(emptyList(), prompt, failure = "题目识别模型配置已被删除或缺少密钥，请重新选择后再试")
        if (snapshot.visionModel.isNotBlank() && vision.model != snapshot.visionModel ||
            snapshot.visionEndpointIdentity.isNotBlank() &&
            endpointIdentityOf(vision.baseUrl) != snapshot.visionEndpointIdentity
        ) {
            return PhotoOutcome(
                emptyList(), prompt,
                failure = "题目识别模型已改变（要求 ${snapshot.visionModel} @ " +
                    "${snapshot.visionEndpointIdentity}），请重新发送而不是重试",
            )
        }
        val encoded = encodeAll(photoPaths)
        if (encoded.failure != null) return encoded
        var usage = AnswerUsage()
        val transcription = try {
            modelClient.completeWithProfile(
                profileId = profileId,
                systemPrompt = VISION_TRANSCRIBE_PROMPT,
                userText = "请完整转写图片中的全部题目内容。",
                imageBase64s = encoded.imageBase64s,
                onUsage = { usage = usage.plus(it) },
            ).trim()
        } catch (error: CancellationException) {
            throw error
        } catch (error: ModelException) {
            throw error // Network/configuration failures must not become a request to retake the photo.
        } catch (_: Exception) {
            null
        }
        if (transcription.isNullOrBlank()) {
            return PhotoOutcome(
                emptyList(), prompt,
                failure = "题目识别没能读出内容，请重拍或在设置里换一个能看图的模型",
            )
        }
        return PhotoOutcome(
            imageBase64s = emptyList(),
            outgoingText = buildString {
                val instruction = prompt.ifBlank {
                    if (snapshot.originalHistory.any { it.role == "assistant" && it.text.isNotBlank() })
                        defaultImagePrompt(snapshot) else ""
                }
                if (instruction.isNotBlank()) append(instruction).append("\n\n")
                append(transcription)
            }.trim(),
            transcript = transcription,
            usage = usage,
        )
    }

    /** 编码全部附件；**数量不符即失败**，绝不偷偷少传一张图。 */
    private suspend fun encodeAll(photoPaths: List<String>): PhotoOutcome {
        val encoded = withContext(Dispatchers.Default) {
            photoPaths.mapNotNull { visionImages.encode(it) }
        }
        return if (encoded.size == photoPaths.size) {
            PhotoOutcome(encoded, "")
        } else {
            PhotoOutcome(emptyList(), "", "有图片读取失败（原附件可能已被清理），请重新选择图片后再发送")
        }
    }

    /**
     * 快照历史 → 发给模型的消息。主模型能看图时回带最近的 [HISTORY_IMAGE_LIMIT] 张历史图
     * （与首次发送复用相同编码）；超出历史附件预算时附说明，避免模型以为图不存在。
     */
    private suspend fun historyFromSnapshot(snapshot: RequestSnapshot): List<ChatMessage> {
        val pathsByIndex = if (snapshot.primaryVisionEnabled) {
            snapshot.originalHistory.withIndex().toList().takeLast(HISTORY_IMAGE_LOOKBACK_MESSAGES).asReversed()
                .filter { it.value.role == "user" }
                .flatMap { message -> message.value.imagePaths.asReversed().map { message.index to it } }
                .take(HISTORY_IMAGE_LIMIT)
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, paths) -> paths.asReversed() }
        } else emptyMap()
        val imagesByIndex = if (pathsByIndex.isEmpty()) emptyMap() else withContext(Dispatchers.Default) {
            pathsByIndex.mapValues { (_, paths) ->
                paths.mapNotNull { visionImages.encode(it) }
            }
        }
        return snapshot.originalHistory.mapIndexed { index, message ->
            val images = imagesByIndex[index].orEmpty()
            val text = when {
                message.role != "user" || message.imagePaths.isEmpty() -> message.text
                images.isEmpty() -> {
                    val note = "（此前附有图片；原图内容请参考当轮回答）"
                    if (message.text.isBlank()) note else "${message.text}\n$note"
                }
                // Photo-only messages retain the user's empty original text in Room.
                // Classify each historical image using only the replies preceding it.
                message.text.isBlank() -> defaultImagePrompt(snapshot, index)
                else -> message.text
            }
            ChatMessage(message.role, text, imageBase64s = images, documentPaths = message.documentPaths,
                turnContext = message.turnContext)
        }.fold(mutableListOf<ChatMessage>()) { result, message ->
            val previous = result.lastOrNull()
            if (previous?.role == "assistant" && message.role == "assistant") {
                result[result.lastIndex] = previous.copy(content = previous.content + "\n\n" + message.content)
            } else result += message
            result
        }
    }

    companion object {
        private fun defaultImagePrompt(snapshot: RequestSnapshot, historyEnd: Int = snapshot.originalHistory.size): String =
            PhotoTurnPrompt.defaultText(ConversationMode.fromName(snapshot.conversationMode),
                snapshot.originalHistory.take(historyEnd).any { it.role == "assistant" && it.text.isNotBlank() })

        const val DEFAULT_VISION_PROMPT = PhotoTurnPrompt.INITIAL_QUESTION

        const val VISION_TRANSCRIBE_PROMPT =
            "你是题目转写器。请把图片中的题目完整转写为 Markdown：中文保持原文；" +
                "数学公式使用 LaTeX 并以 $$...$$ 包裹，块级公式的起止 $$ 各占一行。" +
                "不要解题，不要输出额外解释。"

        /** 快照里记录的联网协议：[AiSearchProtocol.name]，客户端按名解析回枚举。 */
        fun protocolOf(searchProtocol: AiSearchProtocol?): String =
            (searchProtocol ?: AiSearchProtocol.RESPONSES).name
    }
}

/** 历史图号只描述旧回答，不能成为新一轮生成图片的引用。答案字段也要带回文字历史。 */
internal fun historyAnswerText(content: String, finalAnswer: String): String {
    fun historicalFigures(text: String): String = replaceFigureAnchors(text) { anchor ->
        "（历史回答的图 ${anchor.numberText}，新回答需要重新生成图形）"
    }
    val text = historicalFigures(content)
    val answer = historicalFigures(finalAnswer)
    return if (answer.isBlank() || text.contains(answer)) text else "最终答案：\n$answer\n\n$text"
}
