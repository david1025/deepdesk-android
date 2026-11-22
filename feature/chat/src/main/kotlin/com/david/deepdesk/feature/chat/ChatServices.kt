package com.david.deepdesk.feature.chat

import android.content.Context
import com.david.deepdesk.core.common.TimeProvider
import com.david.deepdesk.core.datastore.BalanceRepository
import com.david.deepdesk.core.datastore.SettingsStore
import com.david.deepdesk.core.network.DeepSeekRestApi
import com.david.deepdesk.core.network.FilesApiClient
import com.david.deepdesk.core.network.ToolsApiClient
import com.david.deepdesk.feature.attachments.AttachmentManager
import com.david.deepdesk.feature.chat.data.ChatRepository
import com.david.deepdesk.feature.chat.engine.ChatOrchestrator
import com.david.deepdesk.feature.voice.asr.AsrExecutor
import com.david.deepdesk.feature.voice.recording.RecordingController
import com.david.deepdesk.feature.voice.tts.TtsPlayer

/**
 * 聊天功能依赖集合（轻量手工 DI，§4.1）：由 app 模块构造后注入 ViewModel。
 */
class ChatServices(
    val appContext: Context,
    val repository: ChatRepository,
    val orchestrator: ChatOrchestrator,
    val filesClient: FilesApiClient,
    val toolsClient: ToolsApiClient,
    val restApi: DeepSeekRestApi,
    val attachmentManager: AttachmentManager,
    val recordingController: RecordingController,
    val asrExecutor: AsrExecutor,
    val ttsPlayer: TtsPlayer,
    val settingsStore: SettingsStore,
    val balanceRepository: BalanceRepository,
    val time: TimeProvider,
    val hasApiKey: () -> Boolean,
)
