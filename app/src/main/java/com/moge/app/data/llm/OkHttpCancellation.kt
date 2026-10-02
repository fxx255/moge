package com.moge.app.data.llm

import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

/** Cancellation covers both response headers and all subsequent blocking body reads. */
object OkHttpCancellation {
    // Default completion hooks run too late: a blocked coroutine cannot complete.
    // Keep this internal API isolated here and covered by real socket tests.
    @OptIn(InternalCoroutinesApi::class)
    suspend fun execute(
        client: OkHttpClient,
        request: Request,
        onCall: ((Call) -> Unit)? = null,
    ): Response {
        val context = currentCoroutineContext()
        context.ensureActive()
        val call = client.newCall(request)
        val handle = context[Job]?.invokeOnCompletion(
            onCancelling = true,
            invokeImmediately = true,
        ) { cause -> if (cause != null) call.cancel() }
        try {
            onCall?.invoke(call)
            context.ensureActive()
            val response = call.execute()
            if (context[Job]?.isCancelled == true) {
                response.close()
                context.ensureActive()
            }
            val body = response.body
            if (body == null) {
                handle?.dispose()
                return response
            }
            val source = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    context.ensureActive()
                    return try {
                        super.read(sink, byteCount)
                    } catch (error: IOException) {
                        context.ensureActive()
                        throw error
                    }
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        handle?.dispose()
                    }
                }
            }.buffer()
            return response.newBuilder().body(object : ResponseBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun source() = source
            }).build()
        } catch (error: Throwable) {
            handle?.dispose()
            call.cancel()
            context.ensureActive()
            throw error
        }
    }
}
