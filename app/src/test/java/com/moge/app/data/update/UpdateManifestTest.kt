package com.moge.app.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class UpdateManifestTest {
    private val repository = UpdateRepository.parse("test-owner/moge")!!
    private val bytes = "APK fixture".toByteArray()
    private val signer = "a".repeat(64)
    private val manifest = UpdateManifest(2, "0.2.0", 26, "com.moge.app", bytes.size.toLong(),
        UpdateIntegrity.hex(MessageDigest.getInstance("SHA-256").digest(bytes)), signer,
        repository.assetUrl("v0.2.0", UpdateManifest.APK_NAME), repository.releaseUrl("v0.2.0"), repository.projectUrl, "v0.2.0")
    private val installed = ApkIdentity("com.moge.app", 1, "0.1.0", 26, setOf(signer))
    private val incoming = ApkIdentity("com.moge.app", 2, "0.2.0", 26, setOf(signer))

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }
    private fun parse(text: String) = UpdateManifest.parse(text, repository, "v0.2.0", "com.moge.app")

    @Test fun acceptsOnlyRepositoryIdentifiers() {
        assertNotNull(UpdateRepository.parse("owner-name/Moge.android"))
        listOf("", "https://github.com/owner/repo", "https://evil.invalid/app", "owner/repo/extra", "../repo",
            "owner/..", "owner/.", "owner/repo?token=x", "owner/repo#x", "owner\\repo", "owner/中文",
            "-owner/repo", "owner /repo", "a".repeat(40) + "/repo").forEach { assertNull(it, UpdateRepository.parse(it)) }
    }

    @Test fun startupAttemptPersistsAcrossLaunchesAndClockChanges() {
        val day = 24 * 60 * 60 * 1000L
        val last = day * 10
        assertTrue(shouldCheckOnLaunch(last, 0))
        assertFalse(shouldCheckOnLaunch(last, last))
        assertFalse(shouldCheckOnLaunch(last + day - 1, last))
        assertTrue(shouldCheckOnLaunch(last + day, last))
        // If a user's clock is corrected backwards, checks must not be disabled for weeks.
        assertTrue(shouldCheckOnLaunch(last - day, last))
    }

    @Test fun metadataRoundTripsWithPinnedReleaseUrls() { assertEquals(manifest, parse(manifest.toJson())) }
    @Test fun rejectsForeignAndInsecureAssets() {
        listOf("http://github.com/test-owner/moge/releases/download/v0.2.0/Moge-arm64.apk",
            "https://evil.invalid/Moge-arm64.apk", "https://github.com/other/moge/releases/download/v0.2.0/Moge-arm64.apk",
            manifest.apkUrl + "?token=anything").forEach { rejected { parse(manifest.copy(apkUrl = it).toJson()) } }
    }
    @Test fun rejectsPackageMismatch() { rejected { parse(manifest.copy(packageName = "com.moge.app.debug").toJson()) } }
    @Test fun rejectsBadHashAndCertificate() {
        rejected { parse(manifest.copy(sha256 = "123").toJson()) }
        rejected { parse(manifest.copy(signingCertificateSha256 = "G".repeat(64)).toJson()) }
    }
    @Test fun rejectsUnboundedSize() {
        listOf(0L, -1L, UpdateManifest.MAX_APK_BYTES + 1).forEach { rejected { parse(manifest.copy(size = it).toJson()) } }
    }
    @Test fun rejectsInvalidVersionAndSdk() {
        rejected { parse(manifest.copy(versionCode = 0).toJson()) }
        rejected { parse(manifest.copy(versionCode = 2_100_000_001).toJson()) }
        rejected { parse(manifest.copy(minSdk = 25).toJson()) }
        rejected { parse(manifest.copy(versionName = "0.3.0").toJson()) }
        rejected { parse(manifest.copy(tag = "v0.3.0").toJson()) }
    }
    @Test fun rejectsSchemaAndQuotedNumbers() {
        rejected { parse(manifest.toJson().replace("\"schemaVersion\":1", "\"schemaVersion\":2")) }
        try { parse(manifest.toJson().replace("\"versionCode\":2", "\"versionCode\":\"2\"")); fail() } catch (_: IllegalStateException) { }
    }
    @Test fun rejectsLargeJsonBeforeParsing() {
        rejected { parse(" ".repeat(UpdateManifest.MAX_JSON_BYTES + 1)) }
    }
    @Test fun ignoresDraftAndPrerelease() {
        assertTrue(Json.parseToJsonElement("{\"draft\":false,\"prerelease\":false}").jsonObject.isPublicRelease())
        listOf("{\"draft\":true,\"prerelease\":false}", "{\"draft\":false,\"prerelease\":true}",
            "{\"draft\":\"false\",\"prerelease\":false}", "{}").forEach {
            assertFalse(Json.parseToJsonElement(it).jsonObject.isPublicRelease())
        }
    }
    @Test fun allowsOnlyGithubHttpsTransport() {
        listOf("https://github.com/a", "https://api.github.com/repos/a/b", "https://release-assets.githubusercontent.com/a").forEach {
            assertTrue(allowedUpdateTransport(it.toHttpUrl()))
        }
        listOf("http://github.com/a", "https://evil.invalid/a", "https://github.com.evil.invalid/a", "https://user@github.com/a",
            "https://github.com:8443/a", "https://raw.githubusercontent.com/a").forEach { assertFalse(allowedUpdateTransport(it.toHttpUrl())) }
    }
    @Test fun copiesExactVerifiedBytes() {
        val output = ByteArrayOutputStream()
        assertEquals(bytes.size.toLong(), UpdateIntegrity.copyVerified(ByteArrayInputStream(bytes), output, manifest))
        assertArrayEquals(bytes, output.toByteArray())
    }
    @Test fun rejectsTruncatedOversizedAndCorruptedDownloads() {
        listOf(bytes.copyOf(bytes.size - 1), bytes + 0.toByte(), "BAD fixture".toByteArray()).forEach {
            rejected { UpdateIntegrity.copyVerified(ByteArrayInputStream(it), ByteArrayOutputStream(), manifest) }
        }
    }
    @Test fun cancellationNeverProducesCompletedDownload() {
        val output = ByteArrayOutputStream()
        try {
            UpdateIntegrity.copyVerified(ByteArrayInputStream(bytes), output, manifest, { throw CancellationException() })
            fail()
        } catch (_: CancellationException) { assertEquals(0, output.size()) }
    }
    @Test fun acceptsSameSignerHigherVersion() { UpdateIntegrity.validateIdentity(installed, incoming, manifest, 36) }
    @Test fun rejectsSameAndLowerVersions() {
        listOf(0L, 1L).forEach { rejected {
            UpdateIntegrity.validateIdentity(installed, incoming.copy(versionCode = it), manifest.copy(versionCode = it), 36)
        } }
    }
    @Test fun rejectsDifferentSignerAndForgedManifestSigner() {
        rejected { UpdateIntegrity.validateIdentity(installed, incoming.copy(signerHashes = setOf("b".repeat(64))), manifest, 36) }
        rejected { UpdateIntegrity.validateIdentity(installed, incoming, manifest.copy(signingCertificateSha256 = "b".repeat(64)), 36) }
        rejected { UpdateIntegrity.validateIdentity(installed.copy(signerHashes = emptySet()), incoming, manifest, 36) }
    }
    @Test fun rejectsMismatchedApkMetadataAndUnsupportedAndroid() {
        rejected { UpdateIntegrity.validateIdentity(installed, incoming.copy(packageName = "other.package"), manifest, 36) }
        rejected { UpdateIntegrity.validateIdentity(installed, incoming.copy(versionCode = 3), manifest, 36) }
        rejected { UpdateIntegrity.validateIdentity(installed, incoming.copy(versionName = "0.3.0"), manifest, 36) }
        rejected { UpdateIntegrity.validateIdentity(installed, incoming.copy(minSdk = 37), manifest.copy(minSdk = 37), 36) }
    }
}
