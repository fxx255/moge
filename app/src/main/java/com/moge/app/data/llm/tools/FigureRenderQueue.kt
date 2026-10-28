package com.moge.app.data.llm.tools

import com.moge.app.data.parse.ReplyFigure
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** One solve's structured render jobs. Revisions of one ID run in order; independent IDs can overlap. */
internal class FigureRenderQueue(private val scope: CoroutineScope, private val renderer: FigureToolRenderer) {
    private data class Entry(val figure: ReplyFigure, val completion: Deferred<Unit>)
    private val latest = mutableMapOf<Int, Entry>() // Submitted only by the tool loop.
    private val successful = ConcurrentHashMap<Int, ReplyFigure>()
    private val permits = Semaphore(2)

    fun figure(id: Int): ReplyFigure? = successful[id]

    fun submit(id: Int, figure: ReplyFigure) {
        val previous = latest[id]
        if (previous?.figure == figure &&
            (!previous.completion.isCompleted || successful[id] == figure)) return
        val completion = scope.async(start = CoroutineStart.UNDISPATCHED) {
            previous?.completion?.await()
            if (successful[id] != figure) {
                try {
                    val rendered = permits.withPermit { renderer.render(figure) }
                    currentCoroutineContext().ensureActive()
                    if (rendered) successful[id] = figure
                } catch (cancelled: CancellationException) {
                    // A cancelled renderer must also stop the HTTP continuation in this solve scope.
                    scope.cancel(cancelled)
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the last good revision. Missing slots are repaired by the finalizer.
                }
            }
        }
        latest[id] = Entry(figure, completion)
    }

    suspend fun awaitAll() { latest.values.map { it.completion }.awaitAll() }
}
