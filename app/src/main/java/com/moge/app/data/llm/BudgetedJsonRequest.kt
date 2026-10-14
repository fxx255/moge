package com.moge.app.data.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Shared non-streaming transport with bounded, explicit protocol-parameter negotiation. */
internal suspend fun executeBudgetedJsonRequest(
    client: OkHttpClient, request: Request, payload: JsonObject, usageProtocol: String,
    onUsage: (UsageSample?) -> Unit = {},
): JsonObject {
    val negotiation = GenerationParameterNegotiation(payload)
    while (true) {
        var sample: UsageSample? = null
        try {
            val current = request.newBuilder().post(negotiation.payload.toString().toRequestBody("application/json".toMediaType())).build()
            val body = OkHttpCancellation.execute(client, current).use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 404 && usageProtocol == "responses") throw ModelException(
                    ModelException.Kind.SERVER, "Responses 请求返回 HTTP 404；请核对接口地址和模型名。若此网关仅提供 Chat Completions，请在模型设置切换接口协议",
                )
                if (!response.isSuccessful) throw ModelException(
                    if (response.code in listOf(401, 403)) ModelException.Kind.UNAUTHORIZED else ModelException.Kind.SERVER,
                    "模型接口返回 HTTP ${response.code}：${text.take(2048)}",
                    rejectedHttpStatus = response.code,
                )
                runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    ?: throw ModelException(ModelException.Kind.INVALID_RESPONSE, "模型接口返回了无法解析的结果")
            }
            sample = UsageParser.parse(body, usageProtocol)
            return body
        } catch (error: ModelException) {
            negotiation.afterRejection(error) ?: throw error
        } finally {
            onUsage(sample)
        }
    }
}
