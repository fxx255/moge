package com.moge.app.data.llm

import java.io.IOException
import java.io.FileNotFoundException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** Model transport policy, shared by generation, recognition and explicit connection tests. */
internal object ModelTransport {
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Photos and historical photos may stall a write much longer than a tiny settings ping.
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .eventListenerFactory { it.request().tag(ModelTransportTrace::class.java) ?: EventListener.NONE }
        .build()
}

/** Only phase, counts and error types; never retains URLs, credentials or request content. */
internal class ModelTransportTrace : EventListener() {
    enum class Phase(val label: String) {
        CONNECT("连接"), DNS("域名解析"), TLS("安全握手"), HEADERS("发送请求头"),
        UPLOAD("上传请求内容"), WAIT_RESPONSE("等待响应"), READ_RESPONSE("接收响应"), DONE("完成"),
    }
    @Volatile private var phase = Phase.CONNECT
    @Volatile private var connected = false
    @Volatile private var observed = false
    @Volatile private var reused = false
    @Volatile private var protocol = "unknown"
    @Volatile private var bytes: Long? = null
    @Volatile private var expected = -1L

    override fun callStart(call: Call) {
        observed = true
        expected = runCatching { call.request().body?.contentLength() ?: 0L }.getOrDefault(-1L)
    }
    override fun dnsStart(call: Call, domainName: String) { phase = Phase.DNS }
    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) { phase = Phase.CONNECT }
    override fun secureConnectStart(call: Call) { phase = Phase.TLS }
    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) { connected = true }
    override fun connectionAcquired(call: Call, connection: Connection) {
        reused = !connected
        connected = false
        protocol = connection.protocol().toString()
    }
    override fun requestHeadersStart(call: Call) { phase = Phase.HEADERS }
    override fun requestHeadersEnd(call: Call, request: Request) {
        if (request.body == null) { bytes = 0; phase = Phase.WAIT_RESPONSE }
    }
    override fun requestBodyStart(call: Call) { phase = Phase.UPLOAD }
    override fun requestBodyEnd(call: Call, byteCount: Long) { bytes = byteCount; phase = Phase.WAIT_RESPONSE }
    override fun responseHeadersEnd(call: Call, response: Response) { phase = Phase.READ_RESPONSE }
    override fun responseBodyEnd(call: Call, byteCount: Long) { phase = Phase.DONE }

    fun failure(error: IOException): IOException = if (!observed || error is ModelTransportFailure || error is FileNotFoundException)
        error else ModelTransportFailure(phase, bytes, expected, reused, protocol, error)
}

internal class ModelTransportFailure(
    val phase: ModelTransportTrace.Phase, val bodyBytes: Long?, val expectedBytes: Long,
    val reused: Boolean, val protocol: String, error: IOException,
) : IOException("模型网络请求失败（${phase.label}阶段）", error) {
    fun summary() = "phase=${phase.name} bodyBytes=${bodyBytes ?: "unknown"} expectedBytes=$expectedBytes reused=$reused protocol=$protocol"
}
