package com.moge.app.ui.solve

import com.moge.app.data.db.ConversationBranches
import com.moge.app.data.db.MessageEntity
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.domain.FailureKind
import com.moge.app.domain.RequestStatus
import com.moge.app.runtime.GenerationManager

/** 解题页从上往下的一项：题目 / 追问，或一张解答纸。 */
sealed interface SolveItem {
    val id: String

    /**
     * 用户的提问。[isFirst] 为 true 时画成贴胶带的题目卡，其余是右对齐的追问便利贴。
     * [transcript] 是识题模型的转写，题目卡里可折叠查看。
     */
    data class Question(
        override val id: String,
        val text: String,
        val photoPaths: List<String>,
        val transcript: String,
        val isFirst: Boolean,
        val documentPaths: List<String> = emptyList(),
        val versionIds: List<String> = emptyList(),
        val versionIndex: Int = 0,
    ) : SolveItem

    data class Answer(
        override val id: String,
        val state: AnswerState,
        /** 可见正文：完成时是落库正文，生成中是实时正文，失败/停止时是已生成的半截。 */
        val text: String,
        /** 已渲染的图表（缺图槽位为空串），只在完成态有值。 */
        val figurePaths: List<String> = emptyList(),
        val modelLabel: String = "",
        val durationMs: Long = 0,
        val startedAtMs: Long? = null,
        val networkStatus: com.moge.app.data.llm.NetworkRecoveryStatus? = null,
        /** 生成中的思考过程；只活在内存里，不落库。 */
        val reasoning: String = "",
        /** 未完成的原因（失败态）。 */
        val failureMessage: String = "",
        /** 可「重新发送」时为对应请求 id；null 表示不提供重发入口。 */
        val retryRequestId: String? = null,
        val resumeRequestId: String? = null,
        /** 可「重新生成」时为对应请求 id：只给最后一张已完成或已停止的解答纸。 */
        val regenerateRequestId: String? = null,
        /** 落库的用量（[MessageEntity.usageJson]）；空串 = 未知。 */
        val usageJson: String = "",
        val finalAnswer: String = "",
        val replyToMessageId: String = "",
        val documentPaths: List<String> = emptyList(),
    ) : SolveItem
}

enum class AnswerState {
    /** 已落库、正在准备（转写照片、钉快照）。 */
    PREPARING,

    /** 正在接收正文。 */
    STREAMING,
    COMPLETED,

    /** 中断（网络、超时、进程被杀）：保留半截正文，可重发。 */
    FAILED,

    /** 用户主动停止：保留半截正文，不自动重发。 */
    STOPPED,
}

/** Build the history and branch controls once per database snapshot, not per token. */
internal fun storedSolveItems(
    messages: List<MessageEntity>,
    requests: List<RequestEntity>,
    allMessages: List<MessageEntity> = messages,
): List<SolveItem> {
    val requestByAnswer = requests.associateBy { it.answerMessageId }
    val lastAnswerId = messages.lastOrNull { it.role == ROLE_ASSISTANT }?.id
    var seenQuestion = false
    return messages.map { message ->
        if (message.role != ROLE_ASSISTANT) {
            val versions = ConversationBranches.versions(allMessages, message).map { it.id }
            SolveItem.Question(
                id = message.id,
                text = message.displayContent ?: message.content,
                photoPaths = RequestRepository.decodePathList(message.imagePaths).filter { it.isNotBlank() },
                transcript = message.transcript,
                isFirst = !seenQuestion,
                documentPaths = RequestRepository.decodePathList(message.documentPaths),
                versionIds = versions,
                versionIndex = versions.indexOf(message.id),
            ).also { seenQuestion = true }
        } else {
            answerItem(
                message = message,
                request = requestByAnswer[message.id],
                isLast = message.id == lastAnswerId,
            ).copy(replyToMessageId = message.replyToMessageId.ifBlank { requestByAnswer[message.id]?.userMessageId.orEmpty() },
                documentPaths = (RequestRepository.decodePathList(message.documentPaths) +
                    requestByAnswer[message.id]?.let { RequestRepository.decodePathList(it.documentPaths) }.orEmpty()).distinct())
        }
    }
}

/** Only the live answer changes while reading a stream; reuse every other item and its attachments. */
internal fun List<SolveItem>.withLiveAnswer(conversationId: String?, live: GenerationManager.ActiveState): List<SolveItem> {
    if (conversationId == null) return emptyList()
    if (live.phase?.isInFlight != true || live.conversationId != conversationId) return this
    val index = indexOfLast { it is SolveItem.Answer && it.id == live.answerMessageId }
    if (index < 0) return this
    val stored = this[index] as SolveItem.Answer
    val answer = SolveItem.Answer(
        id = stored.id,
        state = if (live.phase == RequestStatus.PREPARING && live.partialText.isEmpty() && live.reasoning.isBlank())
            AnswerState.PREPARING else AnswerState.STREAMING,
        text = live.partialText, reasoning = live.reasoning, finalAnswer = live.finalAnswer,
        startedAtMs = live.startedAtMs, networkStatus = live.networkStatus,
        replyToMessageId = stored.replyToMessageId, documentPaths = stored.documentPaths,
    )
    return toMutableList().also { it[index] = answer }
}

private fun answerItem(
    message: MessageEntity,
    request: RequestEntity?,
    isLast: Boolean,
): SolveItem.Answer {
    val completed = SolveItem.Answer(
        id = message.id,
        state = AnswerState.COMPLETED,
        text = message.displayContent ?: message.content,
        figurePaths = RequestRepository.decodePathList(message.imagePaths),
        modelLabel = message.modelLabel,
        durationMs = message.durationMs,
        usageJson = message.usageJson,
        finalAnswer = message.finalAnswer,
    )
    // 请求记录一周后会被清理：没有记录的回答按落库正文展示，也就没法重新生成。
    val status = request?.let { RequestStatus.fromName(it.status) } ?: return completed
    val regenerateId = request.requestId.takeIf { isLast }
    return when (status) {
        RequestStatus.COMPLETED -> completed.copy(regenerateRequestId = regenerateId)
        RequestStatus.INTERRUPTED -> SolveItem.Answer(
            id = message.id,
            state = AnswerState.FAILED,
            text = request.partialText,
            failureMessage = request.failureMessage.ifBlank { "这一轮没有完成" },
            retryRequestId = request.requestId.takeIf {
                isLast && FailureKind.fromName(request.failureKind)?.isRetryable != false
            },
            resumeRequestId = request.requestId.takeIf {
                isLast && request.partialText.isNotBlank() && FailureKind.fromName(request.failureKind)?.isRetryable != false
            },
        )
        RequestStatus.CANCELLED -> SolveItem.Answer(
            id = message.id,
            state = AnswerState.STOPPED,
            text = request.partialText,
            regenerateRequestId = regenerateId,
        )
        // 库里在途但管理器没在跑：上个进程遗留，启动扫描马上会把它收成中断。
        RequestStatus.PREPARING, RequestStatus.RUNNING -> SolveItem.Answer(
            id = message.id,
            state = if (request.partialText.isEmpty()) AnswerState.PREPARING else AnswerState.STREAMING,
            text = request.partialText,
        )
    }
}

internal const val ROLE_ASSISTANT = "assistant"
