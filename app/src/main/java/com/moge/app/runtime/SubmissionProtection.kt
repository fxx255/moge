package com.moge.app.runtime

import java.util.concurrent.atomic.AtomicReference

/** Protects the time between a send gesture and the generation task taking ownership. */
class SubmissionProtection internal constructor(
    private val guard: GenerationGuard,
    token: String,
) : AutoCloseable {
    private val pendingToken = AtomicReference<String?>(token)

    internal fun takeToken(): String? = pendingToken.getAndSet(null)

    override fun close() {
        pendingToken.getAndSet(null)?.let(guard::release)
    }
}
