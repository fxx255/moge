package com.moge.app.runtime

import com.moge.app.core.IoDispatcher
import com.moge.app.data.db.ConversationDao
import com.moge.app.data.db.MessageEntity
import com.moge.app.data.db.RequestEntity
import com.moge.app.data.db.RequestRepository
import com.moge.app.data.parse.ReplyParser
import com.moge.app.data.parse.orderedFigures
import com.moge.app.domain.RequestStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Repair recognizable figure metadata in old completed replies, entirely on device. */
@Singleton
class StoredFigureRepair @Inject constructor(
    private val dao: ConversationDao,
    private val renderer: FigureRenderer,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val attempted = linkedSetOf<String>()

    suspend fun repair(messages: List<MessageEntity>, requests: List<RequestEntity>) = withContext(io) {
        val statuses = requests.associate { it.answerMessageId to RequestStatus.fromName(it.status) }
        mutex.withLock {
            for (message in messages) {
                if (message.role != "assistant" || statuses[message.id]?.let { it != RequestStatus.COMPLETED } == true) continue
                val source = message.displayContent ?: message.content
                if (!source.contains("\"plots\"") && !source.contains("\"series\"") && !source.contains("\"diagrams\"")) continue
                val key = "${message.id}:${source.hashCode()}:${message.imagePaths.hashCode()}"
                if (!attempted.add(key)) continue
                if (attempted.size > 256) attempted.remove(attempted.first())
                try {
                    // Existing slots belong to the original rendered answer; do not
                    // replace them with just an embedded subset of its figures.
                    if (RequestRepository.decodePathListStrict(message.imagePaths).any { it.isNotBlank() }) continue
                    val parsed = ReplyParser.parse(source)
                    val figures = parsed.orderedFigures()
                    if (figures.isEmpty()) continue
                    val paths = renderer.render(figures)
                    dao.repairAnswerFigures(
                        message.id, message.content, message.displayContent, message.imagePaths,
                        parsed.reply, RequestRepository.encodePathList(paths),
                    )
                } catch (e: CancellationException) {
                    attempted.remove(key)
                    throw e
                } catch (_: Exception) {
                    // Reading history must remain possible if local rendering/storage fails.
                    // The original provider text remains available for a later retry.
                }
            }
        }
    }
}
