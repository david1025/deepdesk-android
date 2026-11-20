package com.dsh.deepseek

import android.content.Context
import com.dsh.deepseek.core.common.DefaultTimeProvider
import com.dsh.deepseek.core.common.TimeProvider
import com.dsh.deepseek.core.datastore.ApiKeyStore
import com.dsh.deepseek.core.datastore.BalanceRepository
import com.dsh.deepseek.core.datastore.SettingsStore
import com.dsh.deepseek.core.database.AppDatabase
import com.dsh.deepseek.core.network.DeepSeekNetworkFactory
import com.dsh.deepseek.core.network.FilesApiClient
import com.dsh.deepseek.core.network.MessageStreamClient
import com.dsh.deepseek.core.network.ToolsApiClient
import com.dsh.deepseek.feature.attachments.AttachmentManager
import com.dsh.deepseek.feature.chat.ChatServices
import com.dsh.deepseek.feature.chat.data.ChatRepository
import com.dsh.deepseek.feature.chat.engine.ChatOrchestrator
import com.dsh.deepseek.feature.history.HistoryImporterExporter
import com.dsh.deepseek.feature.voice.asr.AsrExecutor
import com.dsh.deepseek.feature.voice.recording.RecordingController
import com.dsh.deepseek.feature.voice.tts.TtsPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * 轻量手工 DI 容器（§4.1 二选一：选手工 DI，避免 12 模块全量接入 Hilt 的构建成本）。
 * 生成任务挂靠 [applicationScope]，独立于 Composable/Activity 生命周期（§3.3）。
 */
class AppContainer(context: Context) {

    val appContext: Context = context.applicationContext

    val time: TimeProvider = DefaultTimeProvider()

    /** 生成/录音/TTS 的应用级作用域：进程存活期间持续运行 */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase = AppDatabase.get(context)

    val apiKeyStore: ApiKeyStore = ApiKeyStore(context)
    val settingsStore: SettingsStore = SettingsStore(context)

    val okHttpClient = DeepSeekNetworkFactory.createOkHttpClient(apiKeyStore)
    val restApi = DeepSeekNetworkFactory.createRestApi(okHttpClient)
    val messageStreamClient = MessageStreamClient(okHttpClient)
    val filesApiClient = FilesApiClient(okHttpClient)
    val toolsApiClient = ToolsApiClient(okHttpClient, restApi)
    val balanceRepository: BalanceRepository = BalanceRepository(
        restApi = restApi,
        settingsStore = settingsStore,
        hasApiKey = { apiKeyStore.isConfigured() },
    )

    val chatRepository: ChatRepository = ChatRepository(database, time)
    val orchestrator: ChatOrchestrator = ChatOrchestrator(applicationScope, messageStreamClient, chatRepository, time)

    val attachmentManager: AttachmentManager = AttachmentManager(context, filesApiClient, time)
    val recordingController: RecordingController = RecordingController(applicationScope)
    val asrExecutor: AsrExecutor = AsrExecutor(toolsApiClient)
    val ttsPlayer: TtsPlayer = TtsPlayer(context, applicationScope, toolsApiClient)

    val chatServices: ChatServices = ChatServices(
        appContext = appContext,
        repository = chatRepository,
        orchestrator = orchestrator,
        filesClient = filesApiClient,
        toolsClient = toolsApiClient,
        attachmentManager = attachmentManager,
        recordingController = recordingController,
        asrExecutor = asrExecutor,
        ttsPlayer = ttsPlayer,
        settingsStore = settingsStore,
        balanceRepository = balanceRepository,
        time = time,
        hasApiKey = { apiKeyStore.isConfigured() },
    )

    val historyImporterExporter: HistoryImporterExporter = HistoryImporterExporter(database, time).apply {
        attachmentDirProvider = { File(appContext.filesDir, "attachments") }
    }

    /** 全量数据清理（设置页“删除全部会话数据”）：Room 级联删除 + 附件目录清空 */
    suspend fun clearAllData() {
        database.conversationDao().deleteAll()
        database.usageDao().deleteAll()
        File(appContext.filesDir, "attachments").deleteRecursively()
    }
}
