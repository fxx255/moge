package com.moge.app.data.db

import com.moge.app.core.IoDispatcher
import com.moge.app.data.llm.RequestSnapshot
import com.moge.app.data.llm.SnapshotCodec
import com.moge.app.data.parse.ConversationTitle
import com.moge.app.domain.FailureKind
import com.moge.app.domain.RequestStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本机在途请求记录仓库。
 *
 * 只做「持久化 + 状态迁移」这一件事：不含任何协程作用域、不持有 ViewModel，
 * 因此可以被应用级管理器安全共享，也能在没有任何界面存活时继续工作。
 */
@Singleton
class RequestRepository @Inject constructor(
    private val dao: RequestDao,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * 事务：用户消息 + 预留回答位置 + 请求记录，**一起**落盘。
     *
     * 三件事必须在同一个事务里，否则会出现两类不可恢复的坏状态：
     * - 用户消息已进会话、请求记录没落盘 → 一条永远无法重试的孤儿用户消息；
     * - 回答占位已进会话、请求没落盘 → 一个永远空着的回答位置。
     *
     * 提交失败时异常向上抛，调用方保留草稿并明确报错。
     */
    suspend fun createRequest(
        conversationId: String,
        userMessageId: String,
        answerMessageId: String,
        attemptId: String,
        userText: String,
        attachmentPaths: List<String>,
        snapshotJson: String,
        /** 用户消息的展示内容（图片题的紧凑文案）；null 表示与正文一致。 */
        userDisplayContent: String? = null,
        status: RequestStatus = RequestStatus.PREPARING,
    ): RequestEntity = withContext(io) {
        // user / answer 两条消息必须严格先后：Instant 落库成 epochMillis，
        // 各自独立 Instant.now() 在同一毫秒内会得到相同 created_at。
        // 只捕获一次时间，answer 显式加 1ms，排序才稳定。
        val now = Instant.now()
        val user = MessageEntity(
            id = userMessageId,
            conversationId = conversationId,
            role = "user",
            content = userText,
            imagePaths = encodeList(attachmentPaths),
            displayContent = userDisplayContent,
            createdAt = now,
        )
        val answer = MessageEntity(
            id = answerMessageId,
            conversationId = conversationId,
            role = "assistant",
            content = "",
            replyToMessageId = userMessageId,
            createdAt = now.plusMillis(1),
        )
        val request = RequestEntity(
            conversationId = conversationId,
            userMessageId = userMessageId,
            answerMessageId = answerMessageId,
            attemptId = attemptId,
            status = status.name,
            userText = userText,
            attachmentPaths = encodeList(attachmentPaths),
            snapshotJson = snapshotJson,
            createdAt = now,
            updatedAt = now,
        )
        dao.insertRequestWithMessages(request, user, answer)
        request
    }

    suspend fun get(requestId: String): RequestEntity? = withContext(io) { dao.getRequest(requestId) }

    suspend fun forConversation(conversationId: String): List<RequestEntity> = withContext(io) {
        dao.getRequestsForConversation(conversationId)
    }

    /**
     * 观察会话的请求记录：状态迁移、重试、终态都会推送，
     * 让界面在不依赖一次性事件的情况下恢复重试入口与进行中状态。
     */
    fun observeForConversation(conversationId: String): Flow<List<RequestEntity>> =
        dao.observeRequestsForConversation(conversationId)

    suspend fun activeForConversation(conversationId: String): RequestEntity? = withContext(io) {
        dao.getActiveRequestForConversation(conversationId)
    }

    suspend fun anyActive(): RequestEntity? = withContext(io) { dao.getAnyActiveRequest() }

    suspend fun countActive(): Int = withContext(io) { dao.countActiveRequests() }

    /**
     * 标记进入 RUNNING（已发出第一个 HTTP 请求）。
     *
     * 带 attemptId + 在途状态条件：旧 attempt 的迟到调用不会把新 attempt 的状态改掉，
     * 也不会把已经收尾的请求复活成 RUNNING。
     */
    suspend fun markRunning(requestId: String, attemptId: String): Boolean = withContext(io) {
        dao.updateStatusIfInFlight(requestId, attemptId, RequestStatus.RUNNING.name, Instant.now()) > 0
    }

    /**
     * 节流写入当前轮部分正文。
     *
     * **只在 PREPARING/RUNNING 时生效**：收尾/取消之后再调不会把终态改回 RUNNING。
     * 返回 false 表示这次写入被拒（attempt 过期或已是终态），属于正常情况。
     */
    suspend fun savePartial(
        requestId: String,
        attemptId: String,
        partialText: String,
        status: RequestStatus = RequestStatus.RUNNING,
    ): Boolean = withContext(io) {
        dao.updatePartial(requestId, attemptId, partialText, status.name, Instant.now()) > 0
    }

    /**
     * 原子收尾：正文 + 图表 + 元数据 + COMPLETED 在**同一个事务**里。
     *
     * 返回 false 表示收尾被拒（attempt 过期 / 已终态 / 回答位置不属于本请求或已被删除）。
     * **调用方必须把它当失败处理**，不能吞掉。
     */
    suspend fun complete(
        requestId: String,
        attemptId: String,
        answerMessageId: String,
        text: String,
        answerImagePaths: List<String> = emptyList(),
        modelLabel: String = "",
        durationMs: Long = 0,
        usageJson: String = "",
        conversationTitle: String? = null,
        finalAnswer: String = "",
    ): Boolean = withContext(io) {
        // 「回答位置已被删除」会在事务内抛异常并回滚；对外统一成 false（收尾被拒）。
        runCatching {
            dao.completeRequestWithAnswer(
                requestId = requestId,
                attemptId = attemptId,
                answerMessageId = answerMessageId,
                text = text,
                meta = AnswerMeta(
                    imagePaths = encodeList(answerImagePaths),
                    modelLabel = modelLabel,
                    durationMs = durationMs,
                    usageJson = usageJson,
                    conversationTitle = ConversationTitle.normalize(conversationTitle),
                    finalAnswer = finalAnswer,
                ),
                updatedAt = Instant.now(),
            )
        }.getOrDefault(false)
    }

    /** 中断：保留已生成的部分正文（**只留在请求记录里**），记录失败归类。 */
    suspend fun interrupt(
        requestId: String,
        attemptId: String,
        partialText: String,
        kind: FailureKind,
        message: String?,
    ): Boolean = withContext(io) {
        dao.interruptRequest(requestId, attemptId, partialText, kind.name, message.orEmpty(), Instant.now())
    }

    /** 取消：保留本机部分正文与终态，同样不写进会话消息。 */
    suspend fun cancel(requestId: String, attemptId: String, partialText: String): Boolean = withContext(io) {
        dao.cancelRequest(requestId, attemptId, partialText, Instant.now())
    }

    /**
     * 换一个 attemptId 并重置状态；用于重试。
     *
     * **原子 CAS**：条件为 `attempt_id = 当前值 AND status = 'INTERRUPTED'`，
     * 所以并发双击重试只有一个能成功。返回 null 表示本次重试没抢到。
     */
    suspend fun beginRetry(
        requestId: String,
        newAttemptId: String,
        refreshedSnapshotJson: String?,
        refreshedAttachments: List<String>?,
        expectedAttemptId: String? = null,
    ): RequestEntity? = withContext(io) {
        val existing = dao.getRequest(requestId) ?: return@withContext null
        if (expectedAttemptId != null && existing.attemptId != expectedAttemptId) return@withContext null
        val updated = dao.beginRetryIfInterrupted(
            requestId = requestId,
            expectedAttemptId = existing.attemptId,
            newAttemptId = newAttemptId,
            snapshotJson = refreshedSnapshotJson ?: existing.snapshotJson,
            attachmentPaths = refreshedAttachments?.let { encodeList(it) } ?: existing.attachmentPaths,
            updatedAt = Instant.now(),
        )
        if (updated == 0) return@withContext null
        dao.getRequest(requestId)
    }

    /**
     * 重新生成：从已结束的记录（完成 / 停止 / 中断）换上新 attempt，回答位置复用。
     * 必须是调用方读到的那个 attempt（[expectedAttemptId]）：期间已被别处重发或重新生成就返回 null。
     */
    suspend fun beginRegenerate(
        requestId: String,
        expectedAttemptId: String,
        newAttemptId: String,
        snapshotJson: String,
    ): RequestEntity? = withContext(io) {
        val updated = dao.beginRegenerateIfFinished(
            requestId = requestId,
            expectedAttemptId = expectedAttemptId,
            newAttemptId = newAttemptId,
            snapshotJson = snapshotJson,
            updatedAt = Instant.now(),
        )
        if (updated == 0) null else dao.getRequest(requestId)
    }

    /**
     * 启动扫描：把**确实遗留**的在途请求转成 INTERRUPTED。
     *
     * [excludeRequestIds] 是当前进程**正在持有**的 requestId：即使初始化被重复调用，
     * 也不能把正在跑的任务标成中断。只改状态、不发网络请求：重启后绝不自动重发。
     */
    suspend fun recoverOrphans(excludeRequestIds: Set<String> = emptySet()): List<RequestEntity> =
        withContext(io) {
            val orphans = dao.getUnfinishedRequests().filterNot { it.requestId in excludeRequestIds }
            val now = Instant.now()
            orphans.forEach { orphan ->
                dao.interruptRequest(
                    requestId = orphan.requestId,
                    attemptId = orphan.attemptId,
                    partialText = orphan.partialText,
                    kind = FailureKind.NETWORK.name,
                    message = "应用上次退出时这一轮还没结束，已停止。可以点重新发送。",
                    updatedAt = now,
                )
            }
            orphans.map { it.copy(status = RequestStatus.INTERRUPTED.name) }
        }

    /**
     * 补写身份快照（request + attempt + 在途三重围栏）。
     *
     * 转写路线下，同一事务里把转写结果写进用户消息正文，界面仍显示用户原文。
     * 返回 false 表示写入被拒，调用方必须把它当失败处理。
     */
    suspend fun saveSnapshotForAttempt(
        requestId: String,
        attemptId: String,
        snapshot: RequestSnapshot,
        owner: RequestEntity? = null,
    ): Boolean = withContext(io) {
        val encoded = SnapshotCodec.encode(snapshot)
        if (encoded.isBlank()) return@withContext false
        val transcribed = snapshot.prepared && snapshot.photoRoute == RequestSnapshot.PHOTO_ROUTE_TRANSCRIBE
        if (transcribed) require(owner?.requestId == requestId && owner.attemptId == attemptId)
        dao.savePreparedSnapshot(
            requestId = requestId,
            attemptId = attemptId,
            snapshotJson = encoded,
            updatedAt = Instant.now(),
            userMessageId = if (transcribed) owner?.userMessageId else null,
            conversationId = if (transcribed) owner?.conversationId else null,
            modelText = if (transcribed) snapshot.sourceUserText else null,
            displayText = if (transcribed) owner?.userText else null,
        )
    }

    suspend fun delete(requestId: String) = withContext(io) { dao.deleteRequest(requestId) }

    /** 删除会话时清理其请求记录（外键也会级联，这里显式删是为了先于会话删除结算）。 */
    suspend fun deleteForConversation(conversationId: String) = withContext(io) {
        dao.deleteRequestsForConversation(conversationId)
    }

    /** 清理一周前已终结的记录，避免表无限增长；在途记录永不清理。 */
    suspend fun purgeStale() = withContext(io) {
        dao.purgeFinishedBefore(Instant.now().minusSeconds(7 * 24 * 3600))
    }

    fun decodeList(raw: String): List<String> = decodePathList(raw)

    private fun encodeList(values: List<String>): String = encodePathList(values)

    companion object {
        private val listJson = Json { ignoreUnknownKeys = true }

        fun decodePathList(raw: String): List<String> = runCatching {
            if (raw.isBlank()) emptyList() else listJson.decodeFromString<List<String>>(raw)
        }.getOrDefault(emptyList())

        /** 与 [decodePathList] 相同，但坏数据直接抛出：孤儿回收宁可放弃本轮，也不能把读不懂的行当成「没引用」。 */
        fun decodePathListStrict(raw: String): List<String> =
            if (raw.isBlank()) emptyList() else listJson.decodeFromString<List<String>>(raw)

        fun encodePathList(values: List<String>): String =
            if (values.isEmpty()) "" else listJson.encodeToString(values)
    }
}
