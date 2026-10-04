package com.moge.app.data.credential

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class AiModelProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val visionEnabled: Boolean,
    val searchProtocol: AiSearchProtocol,
    val reasoningEffort: AiReasoningEffort,
    val hasApiKey: Boolean,
)

data class AiProfileCredentials(
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val searchProtocol: AiSearchProtocol = AiSearchProtocol.RESPONSES,
)

/**
 * 一次调用内**同时**解析出的安全身份与凭证。
 *
 * 为什么必须是同一个动作：分别调 `activeProfile()` 与 `load()` 中间用户可能
 * 切换档案，导致「A 档案的模型 + B 档案的密钥」这种混钥请求。这里在
 * 存储层的同一把锁里取齐，调用方拿到的是一份自洽的身份+凭证。
 */
data class AiResolvedIdentity(
    val profileId: String,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
    val visionEnabled: Boolean,
    val searchProtocol: AiSearchProtocol,
    val reasoningEffort: AiReasoningEffort,
)

/** 联网搜索协议：模型配置里可自行选择，默认 OpenAI Responses API。 */
enum class AiSearchProtocol {
    /** OpenAI Responses API，服务端执行搜索。 */
    RESPONSES,
    /** OpenAI Chat Completions + web_search 工具（如小米 MiMo）。 */
    CHAT_COMPLETIONS,
    /** Anthropic Messages API，支持 Claude 的原生消息与 web_search 工具。 */
    ANTHROPIC,
    /** 关闭联网。 */
    OFF,
}

/** Controls how much hidden reasoning budget the provider should spend per turn. */
enum class AiReasoningEffort {
    LOW,
    MEDIUM,
    HIGH,
}

@Serializable
private data class StoredAiModelProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val model: String,
    val visionEnabled: Boolean,
    val searchProtocol: AiSearchProtocol = AiSearchProtocol.RESPONSES,
    val reasoningEffort: AiReasoningEffort = AiReasoningEffort.LOW,
    val apiKey: String,
) {
    fun summary() = AiModelProfile(
        id,
        name,
        baseUrl,
        model,
        visionEnabled,
        searchProtocol,
        reasoningEffort,
        apiKey.isNotBlank(),
    )
}

/**
 * AI 模型 API 密钥的本机加密存储。
 *
 * AES/GCM 密钥放在 Android Keystore，明文不落盘、不进系统备份
 * （见 `backup_rules.xml`）、不出现在日志里。
 */
@Singleton
class AiCredentialStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun profiles(): List<AiModelProfile> = storedProfiles().map(StoredAiModelProfile::summary)

    @Synchronized
    fun activeProfileId(): String? {
        val profiles = storedProfiles()
        return activeStoredProfile(profiles)?.id
    }

    @Synchronized
    fun activeProfile(): AiModelProfile? {
        val profiles = storedProfiles()
        return activeStoredProfile(profiles)?.summary()
    }

    @Synchronized
    fun upsertProfile(
        id: String?,
        name: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        visionEnabled: Boolean,
        searchProtocol: AiSearchProtocol = AiSearchProtocol.RESPONSES,
        reasoningEffort: AiReasoningEffort = AiReasoningEffort.LOW,
    ): AiModelProfile {
        val cleanName = name.trim()
        val cleanBaseUrl = baseUrl.trim()
        val cleanModel = model.trim()
        require(cleanName.isNotEmpty()) { "请填写配置名称" }
        require(cleanBaseUrl.isNotEmpty()) { "请填写接口地址" }
        require(cleanModel.isNotEmpty()) { "请填写模型名" }

        val profiles = storedProfiles()
        val existing = id?.let { target -> profiles.firstOrNull { it.id == target } }
        val resolvedKey = apiKey.trim().ifEmpty { existing?.apiKey.orEmpty() }
        require(resolvedKey.isNotEmpty()) { "请填写 API 密钥" }
        val saved = StoredAiModelProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = cleanName,
            baseUrl = cleanBaseUrl,
            model = cleanModel,
            visionEnabled = visionEnabled,
            searchProtocol = searchProtocol,
            reasoningEffort = reasoningEffort,
            apiKey = resolvedKey,
        )
        saveProfiles(
            if (existing == null) profiles + saved
            else profiles.map { if (it.id == saved.id) saved else it },
        )
        preferences.edit().putString(KEY_ACTIVE_PROFILE, saved.id).apply()
        return saved.summary()
    }

    @Synchronized
    fun selectProfile(id: String): AiModelProfile? {
        val profile = storedProfiles().firstOrNull { it.id == id } ?: return null
        preferences.edit().putString(KEY_ACTIVE_PROFILE, id).apply()
        return profile.summary()
    }

    @Synchronized
    fun deleteProfile(id: String): AiModelProfile? {
        val remaining = storedProfiles().filterNot { it.id == id }
        saveProfiles(remaining)
        val activeId = preferences.getString(KEY_ACTIVE_PROFILE, null)
        val next = if (activeId == id || remaining.none { it.id == activeId }) remaining.firstOrNull()
        else remaining.firstOrNull { it.id == activeId }
        preferences.edit().apply {
            if (next == null) remove(KEY_ACTIVE_PROFILE) else putString(KEY_ACTIVE_PROFILE, next.id)
        }.apply()
        return next?.summary()
    }

    @Synchronized
    fun questionVisionProfileId(): String? =
        resolveVisionProfile(preferences.getString(KEY_QUESTION_VISION_PROFILE, null))

    @Synchronized
    fun setQuestionVisionProfileId(id: String?) {
        preferences.edit().apply {
            if (id == null) remove(KEY_QUESTION_VISION_PROFILE) else putString(KEY_QUESTION_VISION_PROFILE, id)
        }.apply()
    }

    @Synchronized
    fun credentialsFor(id: String): AiProfileCredentials? =
        storedProfiles().firstOrNull { it.id == id }?.let {
            AiProfileCredentials(it.baseUrl, it.model, it.apiKey, it.searchProtocol)
        }

    /** **同一次锁内**解析活动档案的安全身份与凭证；没有可用档案时返回 null。 */
    @Synchronized
    fun resolveActiveIdentity(): AiResolvedIdentity? =
        activeStoredProfile(storedProfiles())?.id?.let(::resolveIdentityFor)

    /** 按档案 id 解析该档案的**完整身份+凭证**（含 vision 能力与联网协议）。 */
    @Synchronized
    fun resolveIdentityFor(id: String): AiResolvedIdentity? =
        storedProfiles().firstOrNull { it.id == id }
            ?.takeIf { it.apiKey.isNotBlank() }
            ?.let {
                AiResolvedIdentity(
                    profileId = it.id,
                    baseUrl = it.baseUrl,
                    model = it.model,
                    apiKey = it.apiKey,
                    visionEnabled = it.visionEnabled,
                    searchProtocol = it.searchProtocol,
                    reasoningEffort = it.reasoningEffort,
                )
            }

    private fun resolveVisionProfile(id: String?): String? {
        if (id.isNullOrBlank()) return null
        val profile = storedProfiles().firstOrNull { it.id == id } ?: return null
        return if (profile.visionEnabled && profile.apiKey.isNotBlank()) profile.id else null
    }

    private fun storedProfiles(): List<StoredAiModelProfile> {
        val raw = decrypt(KEY_PROFILES) ?: return emptyList()
        return runCatching { json.decodeFromString<List<StoredAiModelProfile>>(raw) }
            .getOrElse {
                preferences.edit().remove(KEY_PROFILES).apply()
                emptyList()
            }
    }

    private fun activeStoredProfile(profiles: List<StoredAiModelProfile>): StoredAiModelProfile? {
        val activeId = preferences.getString(KEY_ACTIVE_PROFILE, null)
        return profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
    }

    private fun saveProfiles(profiles: List<StoredAiModelProfile>) {
        if (profiles.isEmpty()) preferences.edit().remove(KEY_PROFILES).apply()
        else encrypt(KEY_PROFILES, json.encodeToString(profiles))
    }

    private fun encrypt(storageKey: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(storageKey, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    private fun decrypt(storageKey: String): String? {
        val encoded = preferences.getString(storageKey, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, packed.copyOfRange(0, IV_BYTES)),
            )
            cipher.doFinal(packed.copyOfRange(IV_BYTES, packed.size)).toString(Charsets.UTF_8)
        }.getOrElse {
            preferences.edit().remove(storageKey).apply()
            null
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFS_NAME = "ai_credentials_encrypted"
        const val KEY_PROFILES = "model_profiles_v2"
        const val KEY_ACTIVE_PROFILE = "active_model_profile_id"
        const val KEY_QUESTION_VISION_PROFILE = "question_vision_profile_id"
        const val KEY_ALIAS = "moge_ai_profiles_aes_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
