package com.david.deepdesk.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** 用户偏好（非敏感，§6.2）：主题 / 语言 / TTS 音色 */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val language: Language = Language.SYSTEM,
    val voiceId: String = "stella",
    val thinkingEnabledByDefault: Boolean = false,
    val webSearchEnabledByDefault: Boolean = false,
    /** 自动朗读新回答；默认关闭（顶栏显示喇叭带斜杠静音图标） */
    val autoReadEnabled: Boolean = false,
    /** 思考完成后自动折叠；默认开启，对齐官方 App */
    val autoCollapseThinking: Boolean = true,
    /** 聊天输入框下方展示本会话 token 与估算花费；默认开启 */
    val showSessionUsageChip: Boolean = true,
    val cachedBalance: CachedBalance = CachedBalance(),
)

/** 最近一次成功拉取的 DeepSeek 账户余额（本地缓存，供侧栏即时展示）。 */
data class CachedBalance(
    val available: Boolean = false,
    val currency: String = "CNY",
    val total: String? = null,
    val granted: String? = null,
    val toppedUp: String? = null,
    val fetchedAt: Long = 0L,
    val error: String? = null,
) {
    val hasAmount: Boolean get() = total != null
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class Language { SYSTEM, ZH, EN }

class SettingsStore(private val context: Context) {

    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val LANGUAGE = stringPreferencesKey("language")
        val VOICE = stringPreferencesKey("voice_id")
        val THINKING_DEFAULT = stringPreferencesKey("thinking_default")
        val SEARCH_DEFAULT = stringPreferencesKey("search_default")
        val AUTO_READ = stringPreferencesKey("auto_read")
        val AUTO_COLLAPSE_THINKING = stringPreferencesKey("auto_collapse_thinking")
        val SHOW_SESSION_USAGE = stringPreferencesKey("show_session_usage")
        val BALANCE_CURRENCY = stringPreferencesKey("balance_currency")
        val BALANCE_TOTAL = stringPreferencesKey("balance_total")
        val BALANCE_GRANTED = stringPreferencesKey("balance_granted")
        val BALANCE_TOPPED_UP = stringPreferencesKey("balance_topped_up")
        val BALANCE_AT = stringPreferencesKey("balance_at")
        val BALANCE_AVAILABLE = stringPreferencesKey("balance_available")
        val BALANCE_ERROR = stringPreferencesKey("balance_error")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            themeMode = prefs[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            language = prefs[Keys.LANGUAGE]?.let { runCatching { Language.valueOf(it) }.getOrNull() } ?: Language.SYSTEM,
            voiceId = prefs[Keys.VOICE] ?: "stella",
            thinkingEnabledByDefault = prefs[Keys.THINKING_DEFAULT] == "true",
            webSearchEnabledByDefault = prefs[Keys.SEARCH_DEFAULT] == "true",
            autoReadEnabled = prefs[Keys.AUTO_READ] == "true",
            autoCollapseThinking = prefs[Keys.AUTO_COLLAPSE_THINKING] != "false",
            showSessionUsageChip = prefs[Keys.SHOW_SESSION_USAGE] != "false",
            cachedBalance = CachedBalance(
                available = prefs[Keys.BALANCE_AVAILABLE] == "true",
                currency = prefs[Keys.BALANCE_CURRENCY] ?: "CNY",
                total = prefs[Keys.BALANCE_TOTAL],
                granted = prefs[Keys.BALANCE_GRANTED],
                toppedUp = prefs[Keys.BALANCE_TOPPED_UP],
                fetchedAt = prefs[Keys.BALANCE_AT]?.toLongOrNull() ?: 0L,
                error = prefs[Keys.BALANCE_ERROR],
            ),
        )
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setLanguage(language: Language) {
        context.dataStore.edit { it[Keys.LANGUAGE] = language.name }
    }

    suspend fun setVoiceId(voiceId: String) {
        context.dataStore.edit { it[Keys.VOICE] = voiceId }
    }

    suspend fun setDefaults(thinking: Boolean? = null, search: Boolean? = null) {
        context.dataStore.edit {
            thinking?.let { v -> it[Keys.THINKING_DEFAULT] = v.toString() }
            search?.let { v -> it[Keys.SEARCH_DEFAULT] = v.toString() }
        }
    }

    suspend fun setAutoRead(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_READ] = enabled.toString() }
    }

    suspend fun setAutoCollapseThinking(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_COLLAPSE_THINKING] = enabled.toString() }
    }

    suspend fun setShowSessionUsageChip(enabled: Boolean) {
        context.dataStore.edit { it[Keys.SHOW_SESSION_USAGE] = enabled.toString() }
    }

    suspend fun setCachedBalance(balance: CachedBalance) {
        context.dataStore.edit {
            it[Keys.BALANCE_AVAILABLE] = balance.available.toString()
            it[Keys.BALANCE_CURRENCY] = balance.currency
            if (balance.total != null) it[Keys.BALANCE_TOTAL] = balance.total else it.remove(Keys.BALANCE_TOTAL)
            if (balance.granted != null) it[Keys.BALANCE_GRANTED] = balance.granted else it.remove(Keys.BALANCE_GRANTED)
            if (balance.toppedUp != null) it[Keys.BALANCE_TOPPED_UP] = balance.toppedUp else it.remove(Keys.BALANCE_TOPPED_UP)
            it[Keys.BALANCE_AT] = balance.fetchedAt.toString()
            if (balance.error != null) it[Keys.BALANCE_ERROR] = balance.error else it.remove(Keys.BALANCE_ERROR)
        }
    }
}
