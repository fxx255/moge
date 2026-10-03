package com.moge.app.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

class UpdateTransportTest {
    private val repo = UpdateRepository.parse("test-owner/moge")!!
    private val canonical = repo.latestManifestUrl
    private val mirror = "https://ghfast.top/"
    private val seen = CopyOnWriteArrayList<String>()

    private fun transport(code: (String) -> Int = { 200 }, body: (String) -> String = { "metadata" }): UpdateTransport {
        val client = OkHttpClient.Builder().followRedirects(false).addInterceptor { chain ->
            val url = chain.request().url.toString()
            seen.add(url)
            assertNull(chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(code(url)).message("fixture").body(body(url).toResponseBody()).build()
        }.build()
        return UpdateTransport(client, mirror, "Moge-test")
    }

    @Test fun mirrorChecksLatestWithoutCallingTheGithubApi() = runBlocking {
        assertEquals("metadata", transport().asset(canonical) { it.body!!.string() })
        assertEquals(listOf(mirror + canonical), seen)
    }

    @Test fun unavailableMirrorFallsBackToCanonicalAsset() = runBlocking {
        assertEquals("metadata", transport(code = { if (it.startsWith(mirror)) 503 else 200 })
            .asset(canonical) { it.body!!.string() })
        assertEquals(listOf(mirror + canonical, canonical), seen)
    }

    @Test fun invalidMetadataFallsBackBeforeAcceptingAnUpdate() = runBlocking {
        val fixture = UpdateManifest(2, "0.2.0", 26, "com.moge.app", 1, "a".repeat(64), "b".repeat(64),
            repo.assetUrl("v0.2.0", UpdateManifest.APK_NAME), repo.releaseUrl("v0.2.0"), repo.projectUrl,
            "v0.2.0", "本轮更新")
        val result = transport(body = { if (it.startsWith(mirror)) fixture.copy(packageName = "foreign").toJson() else fixture.toJson() })
            .asset(canonical) { UpdateManifest.parse(it.body!!.string(), repo, "v0.2.0", "com.moge.app") }
        assertEquals(fixture, result)
        assertEquals(2, seen.size)
    }

    @Test fun corruptDownloadRestartsAndVerifiesDirectBytes() = runBlocking {
        val bytes = "verified APK".toByteArray()
        val manifest = UpdateManifest(2, "0.2.0", 26, "com.moge.app", bytes.size.toLong(),
            UpdateIntegrity.hex(MessageDigest.getInstance("SHA-256").digest(bytes)), "a".repeat(64),
            repo.assetUrl("v0.2.0", UpdateManifest.APK_NAME), repo.releaseUrl("v0.2.0"), repo.projectUrl, "v0.2.0")
        val output = transport(body = { if (it.startsWith(mirror)) "corrupt bytes" else "verified APK" })
            .asset(manifest.apkUrl) { response ->
                ByteArrayOutputStream().also { destination ->
                    response.body!!.byteStream().use { UpdateIntegrity.copyVerified(it, destination, manifest) }
                }.toByteArray()
            }
        assertArrayEquals(bytes, output)
        assertEquals(listOf(mirror + manifest.apkUrl, manifest.apkUrl), seen)
    }

    @Test fun cancellationDoesNotStartAnotherDownload() = runBlocking {
        try {
            transport().asset(canonical) { throw CancellationException("cancel") }
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(listOf(mirror + canonical), seen)
        }
    }

    @Test fun mirrorCannotRedirectToForeignOrInsecureTargets() {
        val transport = transport()
        assertTrue(transport.allowed((mirror + canonical).toHttpUrl()))
        assertTrue(transport.allowed("https://release-assets.githubusercontent.com/fixture".toHttpUrl()))
        listOf("http://ghfast.top/https://github.com/a/b", "https://ghfast.top/https://evil.invalid/a",
            "https://ghfast.top/https://user@github.com/a", "https://ghfast.top/anything",
            "https://ghfast.top/https://github.com/a?token=x", "https://other.invalid/https://github.com/a")
            .forEach { assertFalse(it, transport.allowed(it.toHttpUrl())) }
    }
}
