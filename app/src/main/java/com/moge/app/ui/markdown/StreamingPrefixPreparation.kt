package com.moge.app.ui.markdown

import com.moge.app.data.parse.prepareReplyMarkdown
import com.moge.app.data.parse.wrapLongFormulas
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun prepareStreamingPrefix(source: String, formulaWidth: Int, textSize: Float): String =
    wrapLongFormulas(prepareReplyMarkdown(source), formulaWidth, formulaWidthMeasurer(textSize))

/** UI-thread mailbox for a single native view. Only immutable text and dimensions go to the worker. */
internal class StreamingPrefixPreparation(
    private val scope: CoroutineScope,
    private val prepare: suspend (String, Int, Float) -> String = { text, width, size ->
        withContext(Dispatchers.Default) { prepareStreamingPrefix(text, width, size) }
    },
) {
    private class Request(val source: String, val tail: String, val width: Int, val size: Float,
        val apply: (String?) -> Unit)
    private var pending: Request? = null
    private var job: Job? = null

    fun clear() {
        job?.cancel()
        job = null
        pending = null
    }

    fun submit(source: String, tail: String, width: Int, size: Float, apply: (String?) -> Unit) {
        pending = Request(source, tail, width, size, apply)
        if (job?.isActive == true) return
        job = scope.launch {
            while (true) {
                val work = pending ?: break
                pending = null
                // A failure falls back to the normal render/error path on the UI thread.
                val prepared = try {
                    prepare(work.source, work.width, work.size)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                val latest = pending
                when {
                    latest == null -> work.apply(prepared)
                    latest.width != work.width || latest.size != work.size -> continue
                    latest.source == work.source -> { pending = null; latest.apply(prepared) }
                    latest.tail.startsWith(work.tail) -> work.apply(prepared)
                    // Rewritten or replaced text supersedes this request; never flash its old prefix.
                }
            }
        }
    }
}
