package com.moge.app.data.llm

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ProtocolException
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

interface NetworkAvailability {
    val available: Boolean
    suspend fun awaitAvailable(timeoutMs: Long): Boolean
}

enum class NetworkRecoveryStatus { WAITING, RETRYING, CONTINUING }

internal fun isPermanentTlsFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
    it is java.security.cert.CertificateException || it is java.security.cert.CertPathValidatorException ||
        it is SSLPeerUnverifiedException || it is SSLProtocolException ||
        it is SSLHandshakeException && it.message.orEmpty().contains("certificate", ignoreCase = true)
}

/** One application-owned attempt. Never reacts to network loss by cancelling a live socket. */
class GenerationNetworkSession(
    private val network: NetworkAvailability,
    private val status: (NetworkRecoveryStatus?) -> Unit = {},
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val waitBudgetMs: Long = 60_000,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<GenerationNetworkSession>
    private class Submission {
        private val phase = AtomicInteger(0) // 0: connecting, 1: possibly sent, 2: connection deadline expired.
        val call = AtomicReference<Call?>()
        val started get() = phase.get() == 1
        val expired get() = phase.get() == 2
        fun start() {
            if (!phase.compareAndSet(0, 1)) {
                if (expired) throw java.net.SocketTimeoutException("连接等待已超时，尚未发送模型请求")
                throw IOException("请求可能已提交，已阻止网络库重复发送")
            }
        }
        fun expire() { if (phase.compareAndSet(0, 2)) call.get()?.cancel() }
    }
    private val clients = IdentityHashMap<OkHttpClient, OkHttpClient>()
    private val continued = AtomicBoolean(false)

    fun claimContinuation(enabled: Boolean): Boolean = enabled && continued.compareAndSet(false, true)

    suspend fun awaitContinuation() {
        status(NetworkRecoveryStatus.CONTINUING)
        try {
            if (!network.awaitAvailable(waitBudgetMs)) throw IOException("等待网络恢复超时，已保留生成进度")
            currentCoroutineContext().ensureActive()
        } finally { status(null) }
    }

    suspend fun execute(client: OkHttpClient, request: Request, onCall: ((Call) -> Unit)?): Response {
        val startedAt = clock.nanoTime()
        fun remaining() = (waitBudgetMs - (clock.nanoTime() - startedAt) / 1_000_000).coerceAtLeast(0)
        val guarded = synchronized(clients) { clients.getOrPut(client) {
            client.newBuilder().retryOnConnectionFailure(false).addNetworkInterceptor { chain ->
                // Mark conservatively before headers: no replay if the provider might have received anything.
                // Redirected GETs remain compatible; model POSTs may be submitted only once.
                chain.request().tag(Submission::class.java)?.let { submission ->
                    if (!submission.started || chain.request().body != null) submission.start()
                }
                chain.proceed(chain.request())
            }.build()
        } }
        try {
            var failure: IOException? = null
            for (attempt in 0..2) {
                currentCoroutineContext().ensureActive()
                if (remaining() <= 0) throw failure ?: IOException("等待网络恢复超时")
                if (!network.available) {
                    status(NetworkRecoveryStatus.WAITING)
                    if (!network.awaitAvailable(remaining())) throw IOException("等待网络恢复超时，尚未发送模型请求")
                }
                if (attempt > 0) {
                    status(NetworkRecoveryStatus.RETRYING)
                    pause(minOf(1_000L shl (attempt - 1), remaining()))
                }
                currentCoroutineContext().ensureActive()
                if (remaining() <= 0) throw failure ?: IOException("等待网络恢复超时")
                val submission = Submission()
                val current = request.newBuilder().apply {
                    request.body?.let { body -> method(request.method, object : RequestBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun isDuplex() = body.isDuplex()
                        // Also blocks OkHttp HTTP 503/408 and POST-preserving redirect follow-ups.
                        override fun isOneShot() = true
                        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                    }) }
                }.tag(Submission::class.java, submission).build()
                val connectionBudget = remaining().coerceAtLeast(1)
                // Child of the current attempt, cancelled when headers arrive. It can cancel only an unsent call.
                val deadline = CoroutineScope(currentCoroutineContext()).launch(Dispatchers.Default) {
                    delay(connectionBudget)
                    submission.expire()
                }
                try {
                    val bounded = guarded.newBuilder()
                        .connectTimeout(minOf(10_000L, connectionBudget), java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build()
                    return OkHttpCancellation.executeOnce(bounded, current) { call ->
                        submission.call.set(call)
                        if (submission.expired) call.cancel()
                        onCall?.invoke(call)
                    }
                } catch (error: IOException) {
                    currentCoroutineContext().ensureActive()
                    if (submission.expired) throw java.net.SocketTimeoutException("模型连接等待超时，尚未发送请求").apply { initCause(error) }
                    if (submission.started || isPermanentTlsFailure(error) || error is ProtocolException ||
                        error is java.io.FileNotFoundException || error is InterruptedIOException && error.message == "interrupted") throw error
                    failure = error
                    if (attempt == 2) throw error
                } finally { deadline.cancel() }
            }
            throw checkNotNull(failure)
        } finally { status(null) }
    }
}
