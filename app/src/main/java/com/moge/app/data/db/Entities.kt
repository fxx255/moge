package com.moge.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.util.UUID

/** 自动保存的历史会话；与用户主动保存的题册快照独立。 */
@Entity(tableName = "conversation", indices = [Index("updated_at")])
data class ConversationEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    /** 历史封面：首张题目照片的本机路径，没有照片时为空。 */
    @ColumnInfo(name = "cover_image", defaultValue = "")
    val coverImage: String = "",
    /** [com.moge.app.domain.SolveMode.name]，记录最近一次提问用的模式。 */
    @ColumnInfo(name = "solve_mode", defaultValue = "")
    val solveMode: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = createdAt,
    @ColumnInfo(defaultValue = "0")
    val pinned: Boolean = false,
)

/** 会话里的一条消息。会话删除时级联删除。 */
@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversation_id")],
)
data class MessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    /** user / assistant。 */
    val role: String,
    val content: String,
    /** 界面精简文本；null 表示直接显示 [content]。 */
    @ColumnInfo(name = "display_content")
    val displayContent: String? = null,
    /** 用户消息：题目照片；助手消息：渲染好的图表 PNG（缺图位置为空串）。JSON 数组。 */
    @ColumnInfo(name = "image_paths", defaultValue = "")
    val imagePaths: String = "",
    /** 识题模型转写出的题目文本（Markdown + LaTeX），题目卡里给用户核对。 */
    @ColumnInfo(defaultValue = "")
    val transcript: String = "",
    /** 回答所用模型的展示名。 */
    @ColumnInfo(name = "model_label", defaultValue = "")
    val modelLabel: String = "",
    /** 本轮生成总耗时；0 = 未知。 */
    @ColumnInfo(name = "duration_ms", defaultValue = "0")
    val durationMs: Long = 0,
    /** [com.moge.app.data.llm.UsageSample] 的 JSON；空串 = 未知。 */
    @ColumnInfo(name = "usage_json", defaultValue = "")
    val usageJson: String = "",
    /** 独立的最终答案；正文仍保存完整解答。 */
    @ColumnInfo(name = "final_answer", defaultValue = "")
    val finalAnswer: String = "",
    /** 回答对应的用户消息；不依赖相邻消息或创建时间猜测。 */
    @ColumnInfo(name = "reply_to_message_id", defaultValue = "")
    val replyToMessageId: String = "",
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
)

/** 用户自定义分类；没有预置分类。 */
@Entity(tableName = "notebook_category", indices = [Index("sort_order")])
data class NotebookCategoryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    @ColumnInfo(name = "sort_order", defaultValue = "0") val sortOrder: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at") val updatedAt: Instant = createdAt,
)

/** 独立收藏快照。源标识仅用于回到历史，不设外键，删除历史不会删除快照。 */
@Entity(
    tableName = "notebook_entry",
    foreignKeys = [ForeignKey(
        entity = NotebookCategoryEntity::class,
        parentColumns = ["id"], childColumns = ["category_id"],
        onDelete = ForeignKey.SET_NULL,
    )],
    indices = [Index("category_id"), Index("updated_at"), Index(value = ["source_answer_id"], unique = true)],
)
data class NotebookEntryEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
    @ColumnInfo(name = "source_conversation_id") val sourceConversationId: String,
    @ColumnInfo(name = "source_question_id") val sourceQuestionId: String,
    @ColumnInfo(name = "source_answer_id") val sourceAnswerId: String,
    val title: String,
    @ColumnInfo(name = "question_text") val questionText: String,
    @ColumnInfo(name = "question_transcript", defaultValue = "") val questionTranscript: String = "",
    /** JSON 数组，引用持久化的题目照片；附件回收须扫描此字段。 */
    @ColumnInfo(name = "question_image_paths", defaultValue = "") val questionImagePaths: String = "",
    @ColumnInfo(name = "answer_text") val answerText: String,
    @ColumnInfo(name = "final_answer", defaultValue = "") val finalAnswer: String = "",
    /** JSON 数组，保留缺图空槽及原编号。 */
    @ColumnInfo(name = "figure_paths", defaultValue = "") val figurePaths: String = "",
    @ColumnInfo(name = "created_at") val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at") val updatedAt: Instant = createdAt,
)

/**
 * 一次**在途生成请求**的本机持久记录。
 *
 * 进程被回收后，它是「回来还能看到失败原因、还能点重试」的唯一依据。
 * 状态机见 [com.moge.app.domain.RequestStatus]：
 * `PREPARING → RUNNING → COMPLETED / INTERRUPTED / CANCELLED`。
 */
@Entity(
    tableName = "request",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversation_id"), Index("status"), Index("updated_at")],
)
data class RequestEntity(
    @PrimaryKey
    @ColumnInfo(name = "request_id")
    val requestId: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    /** 本轮用户消息 id；重试复用它，绝不重复插入用户消息。 */
    @ColumnInfo(name = "user_message_id")
    val userMessageId: String,
    /** 预留的回答位置；重试只更新这一条，不会留下多个半截气泡。 */
    @ColumnInfo(name = "answer_message_id")
    val answerMessageId: String,
    /** 每次重试换一个新的 attemptId；晚到的旧回调凭它作废。 */
    @ColumnInfo(name = "attempt_id")
    val attemptId: String,
    /** [com.moge.app.domain.RequestStatus.name]。 */
    val status: String,
    /** 已确认（上一轮）的正文快照，续写/重试时用它作锚。 */
    @ColumnInfo(name = "confirmed_text")
    val confirmedText: String = "",
    /** 当前轮部分正文，按节流策略落盘；失败时保留为「中断内容」。 */
    @ColumnInfo(name = "partial_text")
    val partialText: String = "",
    /** 已解析出的图槽位（JSON 数组，缺图位置保留 null 占位）。 */
    @ColumnInfo(name = "figure_slots", defaultValue = "")
    val figureSlots: String = "",
    /** 本轮用户输入原文；重试时原样复用。 */
    @ColumnInfo(name = "user_text", defaultValue = "")
    val userText: String = "",
    /** 首次提交时已复制到私有目录的附件绝对路径（JSON 数组）。 */
    @ColumnInfo(name = "attachment_paths", defaultValue = "")
    val attachmentPaths: String = "",
    /** 重试快照（JSON）：模型/端点身份、模式、联网设置与历史。**绝不**含密钥。 */
    @ColumnInfo(name = "snapshot_json", defaultValue = "")
    val snapshotJson: String = "",
    @ColumnInfo(name = "failure_kind", defaultValue = "")
    val failureKind: String = "",
    @ColumnInfo(name = "failure_message", defaultValue = "")
    val failureMessage: String = "",
    /** 已发生的 HTTP 轮次（首轮 + 恢复 + 续写），诊断用。 */
    @ColumnInfo(defaultValue = "0")
    val rounds: Int = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant = Instant.now(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant = createdAt,
)
