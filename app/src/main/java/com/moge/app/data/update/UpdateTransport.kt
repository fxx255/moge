package com.moge.app.data.update

import com.moge.app.data.llm.OkHttpCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Mirror failures, including invalid bytes, retry the canonical asset from the beginning. */
internal class UpdateTransport(
    private val client: OkHttpClient,
    mirror: String,
    private val userAgent: String,
) {
    val mirrorOrigin: HttpUrl? = mirror.toHttpUrlOrNull()?.takeIf {
        it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.port == 443 &&
            it.encodedPath == "/" && it.query == null && it.fragment == null
    }

    fun assetUrls(canonical: String): List<String> {
        val url = canonical.toHttpUrl()
        require(allowedUpdateTransport(url) && url.host == "github.com")
        return listOfNotNull(mirrorOrigin?.let { "$it$canonical" }, canonical).distinct()
    }

    fun allowed(url: HttpUrl): Boolean {
        if (allowedUpdateTransport(url)) return true
        val origin = mirrorOrigin ?: return false
        if (!url.isHttps || url.host != origin.host || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return false
        val nested = url.encodedPath.removePrefix("/").toHttpUrlOrNull() ?: return false
        return allowedUpdateTransport(nested) && nested.host == "github.com" &&
            nested.query == null && nested.fragment == null
    }

    suspend fun <T> asset(canonical: String, consume: suspend (Response) -> T): T {
        var failure: Exception? = null
        for (url in assetUrls(canonical)) {
            currentCoroutineContext().ensureActive()
            try {
                return request(url).use { response ->
                    check(response.isSuccessful) { "Update asset unavailable" }
                    consume(response)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = error
            }
        }
        throw failure ?: IllegalStateException("No update transport")
    }

    suspend fun request(url: String): Response {
        var current = url.toHttpUrl()
        repeat(6) {
            check(allowed(current)) { "Unexpected update redirect" }
            val response = OkHttpCancellation.execute(client, Request.Builder().url(current)
                .header("Accept", "application/json, application/octet-stream")
                .header("Accept-Encoding", "identity").header("User-Agent", userAgent).build())
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            current = response.use {
                current.resolve(it.header("Location") ?: error("Missing redirect")) ?: error("Bad redirect")
            }
        }
        error("Too many redirects")
    }
}
