package com.moge.app.data.llm

import com.moge.app.data.parse.IncrementalReplyDecoder
import com.moge.app.data.parse.ParsedReply
import com.moge.app.domain.ConversationMode
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonPrimitive

/** A tool loop already decided whether the interrupted round has a resumable answer. */
internal class ToolNetworkInterruption(error: Exception) : IOException(error.message, error)

internal fun isRecoverableNetworkFailure(error: Exception): Boolean =
    error !is CancellationException && !isPermanentTlsFailure(error) &&
        generateSequence<Throwable>(error) { it.cause }.none { it is ToolNetworkInterruption } &&
        (error is IOException || error is ModelException && error.kind == ModelException.Kind.NETWORK)

/** Re-encodes only visible text; a continuation can replace an unfinished paragraph without duplicating it. */
internal class ContinuationStreamBridge(
    private val prefix: String,
    private val response: ConversationResponse,
    private val emit: suspend (StreamEvent) -> Unit,
) {
    private val decoder = response.streamDecoder()
    private val text = StringBuilder()
    private var sent: String? = null
    private fun encoded(value: String) = if (response.mode == ConversationMode.SOLVE)
        JsonPrimitive(value).toString().drop(1).dropLast(1) else value

    private suspend fun show(value: String) {
        val previous = sent
        if (previous == null || !value.startsWith(previous)) {
            emit(StreamEvent.AnswerReset)
            emit(StreamEvent.AnswerDelta((if (response.mode == ConversationMode.SOLVE) "{\"reply\":\"" else "") + encoded(value)))
        } else if (value.length > previous.length) emit(StreamEvent.AnswerDelta(encoded(value.drop(previous.length))))
        sent = value
    }

    suspend fun accept(event: StreamEvent) {
        when (event) {
            is StreamEvent.AnswerDelta -> {
                text.append(decoder.append(event.text))
                show(mergeContinuation(prefix, text.toString()))
            }
            StreamEvent.AnswerReset -> { decoder.reset(); text.setLength(0); show(prefix) }
            is StreamEvent.ReasoningDelta -> emit(event)
        }
    }

    suspend fun finish(reply: ParsedReply): ParsedReply {
        val merged = mergeContinuation(prefix, response.reconcile(decoder.finish(), reply.reply))
        show(merged)
        if (response.mode == ConversationMode.SOLVE) emit(StreamEvent.AnswerDelta("\"}"))
        return reply.copy(reply = merged)
    }
}

internal fun recoveredMetadata(reply: ParsedReply, originalRaw: String): ParsedReply {
    fun field(name: String): String {
        val decoder = IncrementalReplyDecoder(name)
        decoder.append(originalRaw)
        return decoder.text.takeIf { decoder.valueComplete }.orEmpty()
    }
    return reply.copy(conversationTitle = field("conversation_title").takeIf { it.isNotBlank() } ?: reply.conversationTitle,
        finalAnswer = field("answer").ifBlank { reply.finalAnswer })
}

/** Fallback for inline solving and plain chat. The harness preserves its own richer tool state. */
internal object NetworkAnswerRecovery {
    suspend fun run(
        response: ConversationResponse, enabled: Boolean, messages: List<ChatMessage>, images: List<String>,
        onEvent: suspend (StreamEvent) -> Unit,
        turnContext: String = "",
        call: suspend (List<ChatMessage>, List<String>, Boolean, suspend (StreamEvent) -> Unit) -> ParsedReply,
    ): ParsedReply {
        val raw = StringBuilder()
        try {
            return call(messages, images, false) { event ->
                when (event) {
                    is StreamEvent.AnswerDelta -> raw.append(event.text)
                    StreamEvent.AnswerReset -> raw.setLength(0)
                    else -> Unit
                }
                onEvent(event)
            }
        } catch (error: Exception) {
            val session = currentCoroutineContext()[GenerationNetworkSession]
            val prefix = response.visible(raw.toString())
            if (!isRecoverableNetworkFailure(error) || prefix.isBlank() || session?.claimContinuation(enabled) != true) throw error
            session.awaitContinuation()
            val userIndex = messages.indexOfLast { it.role == "user" }
            val history = messages.mapIndexed { index, message ->
                if (index == userIndex) message.copy(
                    imageBase64s = images.ifEmpty { message.imageBase64s },
                    turnContext = message.turnContext.ifBlank { turnContext },
                ) else message
            } + ChatMessage("assistant", prefix.takeLast(CONTINUATION_ECHO_CHARS)) + ChatMessage("user", response.continuationInstruction)
            val bridge = ContinuationStreamBridge(prefix, response, onEvent)
            val completed = bridge.finish(call(history, emptyList(), true, bridge::accept))
            return if (response.mode == ConversationMode.SOLVE) recoveredMetadata(completed, raw.toString()) else completed
        }
    }
}
