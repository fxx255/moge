package com.moge.app.ui.markdown

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamingPrefixPreparationTest {
    @Test fun `rewrite replaces queued tails and completion discards an unfinished preparation`() = runTest {
        var gate = CompletableDeferred<Unit>()
        val prepared = mutableListOf<String>()
        val displayed = mutableListOf<String>()
        val queue = StreamingPrefixPreparation(this) { text, _, _ -> prepared += text; gate.await(); text }
        fun submit(text: String) = queue.submit(text, text, 400, 18f) { displayed += requireNotNull(it) }
        submit("old")
        runCurrent()
        submit("old appended")
        submit("replaced")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("old", "replaced"), prepared)
        assertEquals(listOf("replaced"), displayed)

        gate = CompletableDeferred()
        submit("still preparing")
        runCurrent()
        queue.clear()
        displayed += "final"
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("replaced", "final"), displayed)
    }
}
