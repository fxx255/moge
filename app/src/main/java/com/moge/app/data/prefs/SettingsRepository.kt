package com.moge.app.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moge.app.domain.SolveMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 外观：跟随系统 / 方格本（日间）/ 黑板（夜间）。 */
enum class Appearance { SYSTEM, PAPER, CHALK }

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 非敏感偏好快照。模型地址、密钥与识题模型选择在加密的
 * [com.moge.app.data.credential.AiCredentialStore] 里，不在这里。
 */
data class UserSettings(
    val appearance: Appearance = Appearance.SYSTEM,
    val defaultSolveMode: SolveMode = SolveMode.DETAILED,
    val webSearchEnabled: Boolean = false,
    /** 提示词里对用户的称呼；空串表示不称呼。 */
    val nickname: String = "",
    /** 只影响展示：完整解答仍然生成并保存在本机。 */
    val answerFirst: Boolean = false,
    val keepScreenOnInConversation: Boolean = false,
    val questionPhotoPreprocessing: Boolean = true,
    val historyAutoCleanupEnabled: Boolean = true,
    val historyRetentionDays: Int = DEFAULT_HISTORY_RETENTION_DAYS,
) {
    companion object {
        const val DEFAULT_HISTORY_RETENTION_DAYS = 7
        const val MAX_HISTORY_RETENTION_DAYS = 365
    }
}

@Singleton
class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {
    @Inject constructor(@ApplicationContext context: Context) : this(context.settingsDataStore)

    val settings: Flow<UserSettings> = store.data.map { prefs ->
        UserSettings(
            appearance = prefs[KEY_APPEARANCE]
                ?.let { name -> Appearance.entries.firstOrNull { it.name == name } }
                ?: Appearance.SYSTEM,
            defaultSolveMode = SolveMode.fromName(prefs[KEY_SOLVE_MODE]) ?: SolveMode.DETAILED,
            webSearchEnabled = prefs[KEY_WEB_SEARCH] ?: false,
            nickname = prefs[KEY_NICKNAME].orEmpty(),
            answerFirst = prefs[KEY_ANSWER_FIRST] ?: false,
            keepScreenOnInConversation = prefs[KEY_KEEP_SCREEN_ON] ?: false,
            questionPhotoPreprocessing = prefs[KEY_QUESTION_PHOTO_PREPROCESSING] ?: true,
            historyAutoCleanupEnabled = prefs[KEY_HISTORY_AUTO_CLEANUP] ?: true,
            historyRetentionDays = (prefs[KEY_HISTORY_RETENTION_DAYS] ?: UserSettings.DEFAULT_HISTORY_RETENTION_DAYS)
                .coerceIn(1, UserSettings.MAX_HISTORY_RETENTION_DAYS),
        )
    }

    val appearance: Flow<Appearance> = settings.map { it.appearance }

    /** 一次性读取当前快照，供模型客户端、生成管理器等非 UI 调用方使用。 */
    suspend fun current(): UserSettings = settings.first()

    suspend fun setAppearance(value: Appearance) {
        store.edit { it[KEY_APPEARANCE] = value.name }
    }

    suspend fun setDefaultSolveMode(value: SolveMode) {
        store.edit { it[KEY_SOLVE_MODE] = value.name }
    }

    suspend fun setWebSearchEnabled(value: Boolean) {
        store.edit { it[KEY_WEB_SEARCH] = value }
    }

    suspend fun setNickname(value: String) {
        store.edit { it[KEY_NICKNAME] = value.trim().take(MAX_NICKNAME_CHARS) }
    }

    suspend fun setAnswerFirst(value: Boolean) {
        store.edit { it[KEY_ANSWER_FIRST] = value }
    }

    suspend fun setKeepScreenOnInConversation(value: Boolean) {
        store.edit { it[KEY_KEEP_SCREEN_ON] = value }
    }

    suspend fun setQuestionPhotoPreprocessing(value: Boolean) {
        store.edit { it[KEY_QUESTION_PHOTO_PREPROCESSING] = value }
    }

    suspend fun setHistoryAutoCleanupEnabled(value: Boolean) {
        store.edit { it[KEY_HISTORY_AUTO_CLEANUP] = value }
    }

    suspend fun setHistoryRetentionDays(value: Int) {
        require(value in 1..UserSettings.MAX_HISTORY_RETENTION_DAYS) { "保留期限为 1–365 天" }
        store.edit { it[KEY_HISTORY_RETENTION_DAYS] = value }
    }

    private companion object {
        const val MAX_NICKNAME_CHARS = 16
        val KEY_APPEARANCE = stringPreferencesKey("appearance")
        val KEY_SOLVE_MODE = stringPreferencesKey("default_solve_mode")
        val KEY_WEB_SEARCH = booleanPreferencesKey("web_search_enabled")
        val KEY_NICKNAME = stringPreferencesKey("nickname")
        val KEY_ANSWER_FIRST = booleanPreferencesKey("answer_first")
        val KEY_KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_in_conversation")
        val KEY_QUESTION_PHOTO_PREPROCESSING = booleanPreferencesKey("question_photo_preprocessing")
        val KEY_HISTORY_AUTO_CLEANUP = booleanPreferencesKey("history_auto_cleanup")
        val KEY_HISTORY_RETENTION_DAYS = intPreferencesKey("history_retention_days")
    }
}
