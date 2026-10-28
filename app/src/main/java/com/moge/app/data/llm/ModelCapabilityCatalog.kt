package com.moge.app.data.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Capability declarations from /models, not measurements of generated output or execution. */
data class ModelCapability(
    val maxOutputTokens: Int? = null,
    val supportedEfforts: List<String> = emptyList(),
    val fixedEffort: String? = null,
    val defaultEffort: String? = null,
    val supportsReasoning: Boolean? = null,
    val canDisableThinking: Boolean? = null,
    val chatOutputParameter: String? = null,
    val supportsJsonSchema: Boolean? = null,
    val supportsJsonObject: Boolean? = null,
    val supportsFunctionTools: Boolean? = null,
)

data class ModelCatalog(val capabilities: Map<String, ModelCapability>) {
    val ids: List<String> get() = capabilities.keys.sorted()
}

internal fun parseModelCatalog(raw: String): ModelCatalog {
    val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
    val models = (root?.get("data") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)
    fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
    fun effort(value: String?) = value?.lowercase(Locale.ROOT)?.takeIf { it in NATIVE_REASONING_EFFORTS }
    return ModelCatalog(models.mapNotNull { model ->
        val id = model.string("id") ?: return@mapNotNull null
        val efforts = (model["reasoning_supported_efforts"] as? JsonArray).orEmpty()
            .mapNotNull { effort((it as? JsonPrimitive)?.contentOrNull) }.distinct()
        val provider = model["top_provider"] as? JsonObject
        fun positive(source: JsonObject?, key: String) = (source?.get(key) as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
        val parameters = (model["supported_parameters"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        id to ModelCapability(
            maxOutputTokens = positive(model, "max_output_tokens") ?: positive(model, "max_completion_tokens")
                ?: positive(model, "max_tokens") ?: positive(provider, "max_completion_tokens") ?: positive(provider, "max_output_tokens"),
            supportedEfforts = efforts,
            fixedEffort = effort(model.string("reasoning_effort"))?.takeIf { efforts.size <= 1 },
            defaultEffort = effort(model.string("reasoning_default_effort")),
            supportsReasoning = model.flag("supports_reasoning"),
            canDisableThinking = model.flag("can_disable_thinking"),
            supportsJsonSchema = model.flag("supports_json_schema") ?: model.flag("supports_structured_outputs")
                ?: true.takeIf { "structured_outputs" in parameters },
            supportsJsonObject = model.flag("supports_json_object")
                ?: true.takeIf { "response_format" in parameters },
            supportsFunctionTools = model.flag("supports_tools") ?: model.flag("supports_function_calling")
                ?: true.takeIf { "tools" in parameters },
            chatOutputParameter = "max_completion_tokens".takeIf {
                positive(model, it) != null || it in parameters && "max_tokens" !in parameters
            },
        )
    }.toMap())
}

internal val NATIVE_REASONING_EFFORTS = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

/** Small credential-isolated cache. A round pins one immutable capability before generating. */
internal class ModelCapabilityCatalog(private val clock: MonotonicClock = MonotonicClock.SYSTEM) {
    private data class Entry(val catalog: ModelCatalog?, val expiresAt: Long)
    private val mutex = Mutex()
    private val entries = linkedMapOf<String, Entry>()

    private fun cacheKey(baseUrl: String, apiKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8))
        return modelsUrl(baseUrl) + ":" + digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun fetch(client: OkHttpClient, baseUrl: String, apiKey: String): ModelCatalog = mutex.withLock {
        val catalog = request(client, baseUrl, apiKey, automatic = false)
        remember(cacheKey(baseUrl, apiKey), catalog, TimeUnit.MINUTES.toNanos(5))
        catalog
    }

    suspend fun resolve(client: OkHttpClient, baseUrl: String, apiKey: String, model: String): ModelCapability? = mutex.withLock {
        val key = cacheKey(baseUrl, apiKey)
        val entry = entries[key]
        if (entry != null && clock.nanoTime() < entry.expiresAt) return@withLock entry.catalog?.capabilities?.get(model)
        // Every endpoint gets the same discovery policy, independent of model/provider naming.
        val catalog = try {
            request(client, baseUrl, apiKey, automatic = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Metadata outages must not cancel a generation or cause repeated discovery calls.
            remember(key, entry?.catalog, TimeUnit.MINUTES.toNanos(1))
            return@withLock entry?.catalog?.capabilities?.get(model)
        }
        remember(key, catalog, TimeUnit.MINUTES.toNanos(5))
        catalog.capabilities[model]
    }

    private fun remember(key: String, catalog: ModelCatalog?, ttl: Long) {
        entries.remove(key)
        entries[key] = Entry(catalog, clock.nanoTime() + ttl)
        while (entries.size > 12) entries.remove(entries.keys.first())
    }

    private suspend fun request(client: OkHttpClient, baseUrl: String, apiKey: String, automatic: Boolean): ModelCatalog {
        val transport = client.newBuilder()
            .connectTimeout(if (automatic) 3 else 10, TimeUnit.SECONDS)
            .readTimeout(if (automatic) 5 else 20, TimeUnit.SECONDS)
            .callTimeout(if (automatic) 8 else 30, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(modelsUrl(baseUrl)).header("Authorization", "Bearer $apiKey").get().build()
        return OkHttpCancellation.execute(transport, request).use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ModelException(
                if (response.code in listOf(401, 403)) ModelException.Kind.UNAUTHORIZED else ModelException.Kind.SERVER,
                "获取模型列表失败（HTTP ${response.code}）",
            )
            parseModelCatalog(text).also {
                if (it.ids.isEmpty()) throw ModelException(ModelException.Kind.INVALID_RESPONSE, "接口没有返回可用模型")
            }
        }
    }
}
