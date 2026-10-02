package com.moge.app.data.update

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.moge.app.BuildConfig
import com.moge.app.core.ApplicationScope
import com.moge.app.core.IoDispatcher
import com.moge.app.data.llm.OkHttpCancellation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

enum class UpdatePhase {
    UNAVAILABLE, IDLE, CHECKING, CURRENT, AVAILABLE, UNSUPPORTED,
    DOWNLOADING, VERIFYING, READY, AWAITING_PERMISSION, INSTALLER_OPENED, FAILURE,
}
enum class UpdateRetry { CHECK, DOWNLOAD, INSTALL }
data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val candidate: UpdateCandidate? = null,
    val downloadedBytes: Long = 0,
    val retry: UpdateRetry = UpdateRetry.CHECK,
)

/** No model-client interceptors, user credentials, or tokens are used by the updater. */
@Singleton
class AppUpdateRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val repository = UpdateRepository.parse(BuildConfig.UPDATE_REPOSITORY)
    val projectUrl: String? = repository?.projectUrl
    val enabled: Boolean = BuildConfig.UPDATE_ENABLED && !BuildConfig.DEBUG && repository != null
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val updatesDir = File(context.filesDir, "updates")
    private val apk = File(updatesDir, UpdateManifest.APK_NAME)
    private val partial = File(updatesDir, "download.part")
    private val validator = ApkValidator(context)
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.MINUTES).build()
    private val mutableState = MutableStateFlow(AppUpdateState(if (enabled) UpdatePhase.IDLE else UpdatePhase.UNAVAILABLE))
    val state = mutableState.asStateFlow()
    private val mutableStartupNotice = MutableStateFlow<Long?>(null)
    val startupNoticeVersion = mutableStartupNotice.asStateFlow()
    @Volatile private var operation: Job? = null

    init {
        startOperation {
            if (enabled) restorePending() else clearPending()
        }
    }

    /** Main can call this once at startup; errors remain inside update state and never block solving. */
    fun checkOnStartup() {
        if (!enabled) return
        scope.launch(io) {
            // Recovery must finish first; retain an interrupted user's pending installation.
            operation?.join()
            if (mutableState.value.candidate != null) return@launch
            val now = System.currentTimeMillis()
            val last = preferences.getLong("last_check", 0)
            if (!shouldCheckOnLaunch(now, last)) return@launch
            checkInternal(fromLaunch = true)
        }
    }

    fun dismissStartupNotice() { mutableStartupNotice.value = null }

    fun check() = checkInternal(fromLaunch = false)

    private fun checkInternal(fromLaunch: Boolean) {
        if (!enabled || mutableState.value.phase in setOf(UpdatePhase.READY, UpdatePhase.AWAITING_PERMISSION)) return
        startOperation {
            mutableState.value = AppUpdateState(UpdatePhase.CHECKING)
            try {
                // Persist attempts before networking so offline/process-restarted launches stay throttled.
                check(preferences.edit().putLong("last_check", System.currentTimeMillis()).commit())
                val candidate = fetchLatest()
                val phase = when {
                    candidate == null || candidate.manifest.versionCode <= BuildConfig.VERSION_CODE -> UpdatePhase.CURRENT
                    candidate.manifest.minSdk > Build.VERSION.SDK_INT || "arm64-v8a" !in Build.SUPPORTED_ABIS -> UpdatePhase.UNSUPPORTED
                    else -> UpdatePhase.AVAILABLE
                }
                mutableState.value = AppUpdateState(phase, if (phase == UpdatePhase.CURRENT) null else candidate)
                if (fromLaunch && phase == UpdatePhase.AVAILABLE) mutableStartupNotice.value = candidate?.manifest?.versionCode
            } catch (error: CancellationException) {
                mutableState.value = AppUpdateState()
                throw error
            } catch (_: Exception) {
                mutableState.value = AppUpdateState(UpdatePhase.FAILURE)
            }
        }
    }

    fun download() {
        if (!enabled) return
        val candidate = mutableState.value.candidate ?: return
        if (candidate.manifest.minSdk > Build.VERSION.SDK_INT || "arm64-v8a" !in Build.SUPPORTED_ABIS) return
        startOperation {
            mutableState.value = AppUpdateState(UpdatePhase.DOWNLOADING, candidate, retry = UpdateRetry.DOWNLOAD)
            try {
                check(updatesDir.isDirectory || updatesDir.mkdirs())
                clearPending()
                val coroutineContext = currentCoroutineContext()
                request(candidate.manifest.apkUrl).use { response ->
                    check(response.isSuccessful)
                    val body = response.body ?: error("Missing download")
                    check(body.contentLength() == -1L || body.contentLength() == candidate.manifest.size)
                    body.byteStream().use { input -> partial.outputStream().use { output ->
                        UpdateIntegrity.copyVerified(input, output, candidate.manifest,
                            checkCancelled = { coroutineContext.ensureActive() },
                            progress = { bytes -> mutableState.update { it.copy(downloadedBytes = bytes) } })
                    } }
                }
                mutableState.update { it.copy(phase = UpdatePhase.VERIFYING) }
                validator.validate(partial, candidate.manifest)
                coroutineContext.ensureActive()
                check(partial.renameTo(apk))
                check(preferences.edit().putString("pending_manifest", candidate.manifest.toJson())
                    .putBoolean("install_requested", false).commit())
                mutableState.update { it.copy(phase = UpdatePhase.READY, retry = UpdateRetry.INSTALL) }
            } catch (error: CancellationException) {
                clearPending()
                mutableState.value = AppUpdateState(UpdatePhase.AVAILABLE, candidate)
                throw error
            } catch (_: Exception) {
                clearPending()
                mutableState.value = AppUpdateState(UpdatePhase.FAILURE, candidate, retry = UpdateRetry.DOWNLOAD)
            } finally {
                partial.delete()
            }
        }
    }

    fun cancelDownload() {
        if (mutableState.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.VERIFYING)) operation?.cancel()
    }

    /** Validation repeats before granting a read URI, including after process recreation. */
    suspend fun prepareInstall(): Intent? = withContext(io) {
        if (!enabled) return@withContext null
        val candidate = mutableState.value.candidate ?: return@withContext null
        try {
            verifySavedApk(candidate.manifest)
            check(preferences.edit().putBoolean("install_requested", true).commit())
            if (!context.packageManager.canRequestPackageInstalls()) {
                mutableState.update { it.copy(phase = UpdatePhase.AWAITING_PERMISSION, retry = UpdateRetry.INSTALL) }
                return@withContext Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            mutableState.update { it.copy(phase = UpdatePhase.INSTALLER_OPENED, retry = UpdateRetry.INSTALL) }
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("Moge update", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A missing provider/installer can be retried; invalid bytes must be downloaded again.
            val valid = runCatching { verifySavedApk(candidate.manifest) }.isSuccess
            if (!valid) clearPending()
            mutableState.update { it.copy(phase = UpdatePhase.FAILURE,
                retry = if (valid) UpdateRetry.INSTALL else UpdateRetry.DOWNLOAD) }
            null
        }
    }

    suspend fun resumePendingInstall(): Intent? {
        // Denying permission must not reopen Settings in a loop.
        if (enabled && preferences.getBoolean("install_requested", false) &&
            mutableState.value.phase in setOf(UpdatePhase.AWAITING_PERMISSION, UpdatePhase.READY) &&
            context.packageManager.canRequestPackageInstalls()) return prepareInstall()
        return null
    }

    fun installerReturned() {
        preferences.edit().putBoolean("install_requested", false).apply()
        if (mutableState.value.phase == UpdatePhase.INSTALLER_OPENED) mutableState.update { it.copy(phase = UpdatePhase.READY) }
    }

    fun launchFailed() {
        mutableState.update { it.copy(phase = UpdatePhase.FAILURE, retry = UpdateRetry.INSTALL) }
    }

    fun cancelInstall() {
        preferences.edit().putBoolean("install_requested", false).apply()
        mutableState.update { it.copy(phase = UpdatePhase.READY) }
    }

    private suspend fun fetchLatest(): UpdateCandidate? {
        val repo = repository ?: error("No repository")
        val release = request(repo.latestApiUrl).use { response ->
            if (response.code == 404) error("Repository or release unavailable")
            check(response.isSuccessful)
            Json.parseToJsonElement(readJson(response)).jsonObject
        }
        if (!release.isPublicRelease()) return null
        val tag = release["tag_name"]?.jsonPrimitive?.content ?: error("Missing tag")
        check(release["html_url"]?.jsonPrimitive?.content == repo.releaseUrl(tag))
        val assets = release["assets"]?.jsonArray ?: error("Missing assets")
        check(assets.size <= 32)
        fun asset(name: String) = assets.map { it.jsonObject }.single { it["name"]?.jsonPrimitive?.content == name }
        val metadata = asset(UpdateManifest.MANIFEST_NAME)
        val metadataUrl = metadata["browser_download_url"]?.jsonPrimitive?.content ?: error("Missing URL")
        check(metadataUrl == repo.assetUrl(tag, UpdateManifest.MANIFEST_NAME))
        check((metadata["size"]?.jsonPrimitive?.longOrNull ?: 0) in 1..UpdateManifest.MAX_JSON_BYTES.toLong())
        val manifest = request(metadataUrl).use { response ->
            check(response.isSuccessful)
            UpdateManifest.parse(readJson(response), repo, tag, context.packageName)
        }
        val apkAsset = asset(UpdateManifest.APK_NAME)
        check(apkAsset["browser_download_url"]?.jsonPrimitive?.content == manifest.apkUrl)
        check(apkAsset["size"]?.jsonPrimitive?.longOrNull == manifest.size)
        val notes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty().take(16_000)
        return UpdateCandidate(manifest, notes)
    }

    private suspend fun request(url: String): Response {
        var current = url.toHttpUrl()
        repeat(6) {
            check(allowedUpdateTransport(current))
            val response = OkHttpCancellation.execute(client, Request.Builder().url(current)
                .header("Accept", "application/json, application/octet-stream")
                .header("Accept-Encoding", "identity").header("User-Agent", "Moge/${BuildConfig.VERSION_NAME}").build())
            if (response.code !in setOf(301, 302, 303, 307, 308)) return response
            current = response.use { current.resolve(it.header("Location") ?: error("Missing redirect")) ?: error("Bad redirect") }
        }
        error("Too many redirects")
    }

    private suspend fun readJson(response: Response): String {
        val body = response.body ?: error("Missing body")
        check(body.contentLength() <= UpdateManifest.MAX_JSON_BYTES)
        val coroutineContext = currentCoroutineContext()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        body.byteStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= UpdateManifest.MAX_JSON_BYTES)
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun restorePending() {
        partial.delete()
        val saved = preferences.getString("pending_manifest", null) ?: return
        try {
            val tag = Json.parseToJsonElement(saved).jsonObject["tag"]!!.jsonPrimitive.content
            val manifest = UpdateManifest.parse(saved, repository!!, tag, context.packageName)
            verifySavedApk(manifest)
            mutableState.value = AppUpdateState(
                if (preferences.getBoolean("install_requested", false)) UpdatePhase.AWAITING_PERMISSION else UpdatePhase.READY,
                UpdateCandidate(manifest, ""), manifest.size, UpdateRetry.INSTALL)
        } catch (_: Exception) {
            clearPending()
        }
    }

    private fun verifySavedApk(manifest: UpdateManifest) {
        check(apk.isFile)
        apk.inputStream().use { input ->
            UpdateIntegrity.copyVerified(input, object : java.io.OutputStream() {
                override fun write(value: Int) = Unit
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
            }, manifest)
        }
        validator.validate(apk, manifest)
    }

    private fun clearPending() {
        preferences.edit().remove("pending_manifest").remove("install_requested").commit()
        apk.delete()
        partial.delete()
    }

    @Synchronized
    private fun startOperation(block: suspend () -> Unit) {
        if (operation?.isActive == true) return
        operation = scope.launch(io, start = CoroutineStart.LAZY) { block() }.also { it.start() }
    }
}
