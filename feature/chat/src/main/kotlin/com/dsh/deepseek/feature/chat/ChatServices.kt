package com.dsh.deepseek.feature.chat

import android.content.Context
import com.dsh.deepseek.core.common.TimeProvider
import com.dsh.deepseek.core.datastore.BalanceRepository
import com.dsh.deepseek.core.datastore.SettingsStore
import com.dsh.deepseek.core.network.FilesApiClient
import com.dsh.deepseek.core.network.ToolsApiClient
import com.dsh.deepseek.feature.attachments.AttachmentManager
import com.dsh.deepseek.feature.chat.data.ChatRepository
import com.dsh.deepseek.feature.chat.engine.ChatOrchestrator
import com.dsh.deepseek.feature.voice.asr.AsrExecutor
import com.dsh.deepseek.feature.voice.recording.RecordingController
import com.dsh.deepseek.feature.voice.tts.TtsPlayer

/**
 * 聊天功能依赖集合（轻量手工 DI，§4.1）：由 app 模块构造后注入 ViewModel。
 */
class ChatServices(
    val appContext: Context,
    val repository: ChatRepository,
    val orchestrator: ChatOrchestrator,
    val filesClient: FilesApiClient,
    val toolsClient: ToolsApiClient,
    val attachmentManager: AttachmentManager,
    val recordingController: RecordingController,
    val asrExecutor: AsrExecutor,
    val ttsPlayer: TtsPlayer,
    val settingsStore: SettingsStore,
    val balanceRepository: BalanceRepository,
    val time: TimeProvider,
    val hasApiKey: () -> Boolean,
)
