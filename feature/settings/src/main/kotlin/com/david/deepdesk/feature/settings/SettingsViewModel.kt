package com.david.deepdesk.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.david.deepdesk.core.common.AppLog
import com.david.deepdesk.core.datastore.ApiKeyStore
import com.david.deepdesk.core.datastore.Language
import com.david.deepdesk.core.datastore.SettingsStore
import com.david.deepdesk.core.datastore.ThemeMode
import com.david.deepdesk.core.model.ModelsResponse
import com.david.deepdesk.core.network.DeepSeekApiException
import com.david.deepdesk.core.network.DeepSeekRestApi
import com.david.deepdesk.core.ui.AppLocales
import com.david.deepdesk.core.ui.R as UiR
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置页 ViewModel（F01/F17）：
 * - API Key 填写/验证（GET /models 区分 401/402/限额）/保存/删除
 * - 主题、语言、TTS 音色
 * - 数据管理（清理附件、删除全部会话）
 */
class SettingsViewModel(
    private val appContext: Context,
    private val apiKeyStore: ApiKeyStore,
    private val settingsStore: SettingsStore,
    private val restApi: DeepSeekRestApi,
    /** 全量清理动作由 app 层组装（会话+附件），设置模块不直接依赖存储实现 */
    private val clearAllDataAction: suspend () -> Unit,
) : ViewModel() {

    data class KeyState(
        val configured: Boolean = false,
        val testing: Boolean = false,
        val testResult: String? = null,
        val testOk: Boolean = false,
    )

    val settings: StateFlow<com.david.deepdesk.core.datastore.AppSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, com.david.deepdesk.core.datastore.AppSettings())

    private val _keyState = MutableStateFlow(KeyState(configured = apiKeyStore.isConfigured()))
    val keyState: StateFlow<KeyState> = _keyState

    fun saveKey(key: String) {
        if (key.isBlank()) {
            _keyState.value = _keyState.value.copy(
                testResult = appContext.getString(UiR.string.api_key_empty),
                testOk = false,
            )
            return
        }
        try {
            apiKeyStore.save(key.trim())
            _keyState.value = KeyState(
                configured = true,
                testResult = appContext.getString(UiR.string.api_key_saved),
                testOk = true,
            )
        } catch (e: Exception) {
            _keyState.value = _keyState.value.copy(
                testResult = appContext.getString(UiR.string.api_key_save_failed, e.message ?: ""),
                testOk = false,
            )
        }
    }

    fun deleteKey() {
        apiKeyStore.delete()
        _keyState.value = KeyState(
            configured = false,
            testResult = appContext.getString(UiR.string.api_key_deleted),
            testOk = false,
        )
    }

    /** GET /models 验证：401=密钥错误、402=余额、429=限流（§9） */
    fun testKey() {
        _keyState.value = _keyState.value.copy(testing = true, testResult = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val models: ModelsResponse = restApi.listModels()
                val ids = models.data.mapNotNull { it.id }
                val modelList = ids.joinToString("、").ifBlank {
                    appContext.getString(UiR.string.api_key_models_empty)
                }
                _keyState.value = KeyState(
                    configured = true,
                    testing = false,
                    testOk = true,
                    testResult = appContext.getString(UiR.string.api_key_verify_ok, modelList),
                )
            } catch (e: DeepSeekApiException) {
                AppLog.w(TAG, "模型验证失败: ${e.httpStatus}")
                _keyState.value = KeyState(
                    configured = apiKeyStore.isConfigured(),
                    testing = false,
                    testOk = false,
                    testResult = appContext.getString(
                        UiR.string.api_key_http_error,
                        e.toAppError().message,
                        (e.httpStatus ?: "-").toString(),
                    ),
                )
            } catch (e: Exception) {
                _keyState.value = KeyState(
                    configured = apiKeyStore.isConfigured(),
                    testing = false,
                    testOk = false,
                    testResult = appContext.getString(UiR.string.network_error, e.message ?: ""),
                )
            }
        }
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { settingsStore.setThemeMode(mode) }
    fun setLanguage(language: Language) = viewModelScope.launch {
        settingsStore.setLanguage(language)
        withContext(Dispatchers.Main) { AppLocales.apply(language) }
    }
    fun setVoice(voiceId: String) = viewModelScope.launch { settingsStore.setVoiceId(voiceId) }
    fun setThinkingDefault(enabled: Boolean) = viewModelScope.launch { settingsStore.setDefaults(thinking = enabled) }
    fun setSearchDefault(enabled: Boolean) = viewModelScope.launch { settingsStore.setDefaults(search = enabled) }
    fun setAutoCollapseThinking(enabled: Boolean) = viewModelScope.launch { settingsStore.setAutoCollapseThinking(enabled) }

    fun clearAllData(onDone: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { clearAllDataAction() }
                .onFailure { AppLog.e(TAG, "清理数据失败", it) }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    companion object { const val TAG = "Settings" }
}
