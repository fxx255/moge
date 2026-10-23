package com.moge.app.runtime

import com.moge.app.data.db.MessageEntity
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.llm.HISTORY_MAX_CHARS
import com.moge.app.data.llm.HISTORY_MAX_MESSAGES
import com.moge.app.data.llm.RequestSnapshot
import com.moge.app.data.llm.SnapshotCodec
import com.moge.app.data.llm.SnapshotHistoryMessage
import com.moge.app.domain.ConversationMode

/** Freezes model history independently of later display normalization, title edits and retries. */
internal object ModelHistorySnapshot {
    fun capture(
        source: List<MessageEntity>,
        requests: List<RequestEntity>,
        mode: ConversationMode,
    ): List<SnapshotHistoryMessage> {
        val ordered = requests.sortedBy { it.createdAt }
        val snapshots = ordered.mapNotNull { request ->
            SnapshotCodec.decode(request.snapshotJson)?.takeIf { it.modelHistoryVersion == 1 }
                ?.let { request to it }
        }
        val owners = ordered.flatMap { listOf(it.userMessageId to it, it.answerMessageId to it) }.toMap()
        val preparedUsers = snapshots.filter { it.second.prepared }.associateBy { it.first.userMessageId }
        val frozen = mutableMapOf<String, SnapshotHistoryMessage>()
        snapshots.asReversed().forEach { (_, snapshot) ->
            snapshot.originalHistory.forEach { message ->
                val owner = owners[message.id]
                if (owner == null || owner.attemptId == message.sourceAttemptId) {
                    frozen.putIfAbsent(message.id, message)
                }
            }
        }
        val messages = source.filterNot { it.role == "assistant" && it.content.isBlank() }.map { message ->
            val prepared = preparedUsers[message.id]
            when {
                message.role == "user" && prepared != null -> {
                    val (request, snapshot) = prepared
                    SnapshotHistoryMessage(
                        id = message.id,
                        role = "user",
                        text = snapshot.sourceUserText,
                        imagePaths = if (snapshot.photoRoute == RequestSnapshot.PHOTO_ROUTE_DIRECT)
                            RequestRepository.decodePathList(request.attachmentPaths) else emptyList(),
                        documentPaths = snapshot.documentPaths,
                        turnContext = snapshot.prompt?.volatileSystem.orEmpty(),
                        sourceAttemptId = request.attemptId,
                    )
                }
                frozen.containsKey(message.id) -> frozen.getValue(message.id)
                else -> SnapshotHistoryMessage(
                    id = message.id,
                    role = message.role,
                    text = if (message.role == "assistant") {
                        val text = message.displayContent ?: message.content
                        if (mode == ConversationMode.CHAT) text else historyAnswerText(text, message.finalAnswer)
                    } else message.content,
                    imagePaths = if (message.role == "user") RequestRepository.decodePathList(message.imagePaths) else emptyList(),
                    documentPaths = if (message.role == "user") RequestRepository.decodePathList(message.documentPaths) else emptyList(),
                    sourceAttemptId = owners[message.id]?.attemptId.orEmpty(),
                )
            }
        }
        var chars = 0
        val kept = ArrayDeque<SnapshotHistoryMessage>()
        for (message in messages.takeLast(HISTORY_MAX_MESSAGES).asReversed()) {
            val cost = message.text.length + message.turnContext.length + 8
            if (kept.isNotEmpty() && chars + cost > HISTORY_MAX_CHARS) break
            kept.addFirst(message)
            chars += cost
        }
        return kept.toList()
    }
}
