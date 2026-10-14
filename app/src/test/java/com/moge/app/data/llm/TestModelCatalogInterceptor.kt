package com.moge.app.data.llm

import kotlinx.serialization.json.*
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** Metadata discovery is independent from each test's billed generation sequence. */
internal class TestModelCatalogInterceptor(
    private val maxOutput: Int = 200000,
    private val efforts: List<String> = listOf("low", "medium", "high"),
    private val canDisable: Boolean = false,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.method != "GET" || !request.url.encodedPath.endsWith("/models")) return chain.proceed(request)
        val body = buildJsonObject { put("data", buildJsonArray {
            for (model in listOf("test-model", "stepfun/step-5-preview", "deepseek-chat")) add(buildJsonObject {
                put("id", model); put("max_output_tokens", maxOutput)
                put("reasoning_supported_efforts", JsonArray(efforts.map(::JsonPrimitive)))
                put("can_disable_thinking", canDisable)
            })
        }) }
        return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test metadata")
            .body(body.toString().toResponseBody("application/json".toMediaType())).build()
    }
}

internal fun ModelClient.withTestModelCatalog(): ModelClient = apply {
    val field = ModelClient::class.java.getDeclaredField("client").apply { isAccessible = true }
    val transport = field.get(this) as okhttp3.OkHttpClient
    field.set(this, transport.newBuilder().addInterceptor(TestModelCatalogInterceptor()).build())
}
