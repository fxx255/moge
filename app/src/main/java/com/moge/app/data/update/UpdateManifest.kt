package com.moge.app.data.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal fun shouldCheckOnLaunch(now: Long, previousAttempt: Long): Boolean =
    previousAttempt <= 0 || now < previousAttempt || now - previousAttempt >= TimeUnit.HOURS.toMillis(24)

/** Metadata always identifies canonical GitHub assets, independently of their transport. */
class UpdateRepository private constructor(val owner: String, val name: String) {
    val identifier: String get() = "$owner/$name"
    val projectUrl: String get() = "https://github.com/$identifier"
    val latestApiUrl: String get() = "https://api.github.com/repos/$identifier/releases/latest"
    val latestManifestUrl: String get() = "$projectUrl/releases/latest/download/${UpdateManifest.MANIFEST_NAME}"

    fun releaseUrl(tag: String): String = "$projectUrl/releases/tag/${segment(tag)}"
    fun assetUrl(tag: String, asset: String): String =
        "$projectUrl/releases/download/${segment(tag)}/${segment(asset)}"

    companion object {
        fun parse(value: String): UpdateRepository? {
            if (!Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?/[A-Za-z0-9_.-]{1,100}").matches(value)) return null
            val (owner, name) = value.split('/')
            if (name == "." || name == "..") return null
            return UpdateRepository(owner, name)
        }

        private fun segment(value: String): String = HttpUrl.Builder()
            .scheme("https").host("github.com").addPathSegment(value).build().encodedPath.substring(1)
    }
}

data class UpdateManifest(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val packageName: String,
    val size: Long,
    val sha256: String,
    val signingCertificateSha256: String,
    val apkUrl: String,
    val releaseUrl: String,
    val repositoryUrl: String,
    val tag: String,
    val releaseNotes: String = "",
) {
    fun toJson(): String = JsonObject(mapOf(
        "schemaVersion" to JsonPrimitive(1), "versionCode" to JsonPrimitive(versionCode),
        "versionName" to JsonPrimitive(versionName), "minSdk" to JsonPrimitive(minSdk),
        "packageName" to JsonPrimitive(packageName), "size" to JsonPrimitive(size),
        "sha256" to JsonPrimitive(sha256), "signingCertificateSha256" to JsonPrimitive(signingCertificateSha256),
        "apkUrl" to JsonPrimitive(apkUrl), "releaseUrl" to JsonPrimitive(releaseUrl),
        "repositoryUrl" to JsonPrimitive(repositoryUrl), "tag" to JsonPrimitive(tag),
        "abi" to JsonPrimitive("arm64-v8a"),
        "releaseNotes" to JsonPrimitive(releaseNotes),
    )).toString()

    companion object {
        const val MAX_APK_BYTES: Long = 512L * 1024 * 1024
        const val MAX_JSON_BYTES: Int = 256 * 1024
        const val APK_NAME = "Moge-arm64.apk"
        const val MANIFEST_NAME = "update.json"

        fun parse(text: String, repository: UpdateRepository, releaseTag: String, expectedPackage: String): UpdateManifest {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES)
            val root = Json.parseToJsonElement(text).jsonObject
            fun string(key: String) = root[key]?.jsonPrimitive?.takeIf { it.isString }?.content ?: error("Missing string")
            fun integer(key: String) = root[key]?.jsonPrimitive?.takeUnless { it.isString }?.intOrNull ?: error("Missing integer")
            fun long(key: String) = root[key]?.jsonPrimitive?.takeUnless { it.isString }?.longOrNull ?: error("Missing number")
            require(integer("schemaVersion") == 1)
            val versionName = string("versionName")
            require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:[.-][A-Za-z0-9.-]+)?").matches(versionName) && versionName.length <= 80)
            require(releaseTag == "v$versionName" && string("tag") == releaseTag)
            val versionCode = long("versionCode")
            require(versionCode in 1..2_100_000_000L)
            val size = long("size")
            require(size in 1..MAX_APK_BYTES)
            val minSdk = integer("minSdk")
            require(minSdk in 26..100)
            require(string("packageName") == expectedPackage && string("abi") == "arm64-v8a")
            val hash = string("sha256")
            val certificate = string("signingCertificateSha256")
            require(Regex("[a-f0-9]{64}").matches(hash) && Regex("[a-f0-9]{64}").matches(certificate))
            val apkUrl = string("apkUrl")
            val releaseUrl = string("releaseUrl")
            val repositoryUrl = string("repositoryUrl")
            require(apkUrl == repository.assetUrl(releaseTag, APK_NAME))
            require(releaseUrl == repository.releaseUrl(releaseTag) && repositoryUrl == repository.projectUrl)
            return UpdateManifest(versionCode, versionName, minSdk, expectedPackage, size, hash, certificate,
                apkUrl, releaseUrl, repositoryUrl, releaseTag,
                root["releaseNotes"]?.jsonPrimitive?.takeIf { it.isString }?.content.orEmpty().take(16_000))
        }
    }
}

data class UpdateCandidate(val manifest: UpdateManifest, val notes: String)

/** Tests and Android validation share this rule, including the current signing identity. */
data class ApkIdentity(val packageName: String, val versionCode: Long, val versionName: String?,
    val minSdk: Int, val signerHashes: Set<String>)

object UpdateIntegrity {
    fun validateIdentity(installed: ApkIdentity, downloaded: ApkIdentity, manifest: UpdateManifest, sdk: Int) {
        require(downloaded.packageName == installed.packageName && downloaded.packageName == manifest.packageName)
        require(downloaded.versionCode == manifest.versionCode && downloaded.versionCode > installed.versionCode)
        require(downloaded.versionName == manifest.versionName && downloaded.minSdk == manifest.minSdk && downloaded.minSdk <= sdk)
        require(installed.signerHashes.isNotEmpty() && downloaded.signerHashes == installed.signerHashes)
        require(downloaded.signerHashes == setOf(manifest.signingCertificateSha256))
    }

    /** Bounded stream; cancellation is checked before every blocking read and write. */
    fun copyVerified(input: InputStream, output: OutputStream, manifest: UpdateManifest,
        checkCancelled: () -> Unit = {}, progress: (Long) -> Unit = {}): Long {
        require(manifest.size in 1..UpdateManifest.MAX_APK_BYTES)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            require(total + count <= manifest.size)
            checkCancelled()
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            total += count
            progress(total)
        }
        require(total == manifest.size && hex(digest.digest()) == manifest.sha256)
        return total
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** HTTPS redirects may only reach GitHub's release asset hosts, never arbitrary endpoints. */
internal fun allowedUpdateTransport(url: HttpUrl): Boolean = url.isHttps && url.username.isEmpty() &&
    url.password.isEmpty() && url.port == 443 && url.host in setOf(
        "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com",
    )

internal fun JsonObject.isPublicRelease(): Boolean =
    this["draft"]?.jsonPrimitive?.takeUnless { it.isString }?.booleanOrNull == false &&
        this["prerelease"]?.jsonPrimitive?.takeUnless { it.isString }?.booleanOrNull == false
