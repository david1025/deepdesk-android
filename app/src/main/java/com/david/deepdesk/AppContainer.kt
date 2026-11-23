package com.david.deepdesk

import android.content.Context
import com.david.deepdesk.core.common.DefaultTimeProvider
import com.david.deepdesk.core.common.TimeProvider
import com.david.deepdesk.core.datastore.ApiKeyStore
import com.david.deepdesk.core.datastore.BalanceRepository
import com.david.deepdesk.core.datastore.SettingsStore
import com.david.deepdesk.core.database.AppDatabase
import com.david.deepdesk.core.network.DeepSeekNetworkFactory
import com.david.deepdesk.core.network.FilesApiClient
import com.david.deepdesk.core.network.MessageStreamClient
import com.david.deepdesk.core.network.ToolsApiClient
import com.david.deepdesk.feature.attachments.AttachmentManager
import com.david.deepdesk.feature.chat.ChatServices
import com.david.deepdesk.feature.chat.data.ChatRepository
import com.david.deepdesk.feature.chat.engine.ChatOrchestrator
import com.david.deepdesk.feature.history.HistoryImporterExporter
import com.david.deepdesk.feature.voice.asr.AsrExecutor
import com.david.deepdesk.feature.voice.recording.RecordingController
import com.david.deepdesk.feature.voice.tts.TtsPlayer
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
    val orchestrator = ChatOrchestrator(applicationScope, messageStreamClient, chatRepository, time, filesApiClient)

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
        restApi = restApi,
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
