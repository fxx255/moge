package com.moge.app.data.llm

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InterruptedIOException
import java.io.FileNotFoundException

/** Covers body reads as well as headers, while preserving coroutine cancellation. */
internal suspend inline fun <T> withModelNetworkErrors(block: () -> T): T = try {
    block()
} catch (error: FileNotFoundException) {
    // Missing local document attachments keep their own failure classification.
    throw error
} catch (error: IOException) {
    currentCoroutineContext().ensureActive()
    val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    val phase = causes.filterIsInstance<ModelTransportFailure>().firstOrNull()?.phase
        ?.let { "（${it.label}阶段）" }.orEmpty()
    throw ModelException(
        ModelException.Kind.NETWORK,
        if (causes.any { it is InterruptedIOException }) "模型连接超时$phase，请检查网络后重试"
        else "模型网络连接已中断$phase，请检查网络及息屏联网设置后重试",
    ).apply { initCause(error) }
}
