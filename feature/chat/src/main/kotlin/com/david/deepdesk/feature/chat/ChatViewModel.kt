package com.david.deepdesk.feature.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.david.deepdesk.core.common.AppError
import com.david.deepdesk.core.common.AppLog
import com.david.deepdesk.core.model.ApiProtocol
import com.david.deepdesk.core.model.Attachment
import com.david.deepdesk.core.model.ChatMessage
import com.david.deepdesk.core.model.ChatModels
import com.david.deepdesk.core.model.ListedModel
import com.david.deepdesk.core.model.toListedModel
import com.david.deepdesk.core.model.ReplyBlock
import com.david.deepdesk.core.model.ReplyStatus
import com.david.deepdesk.core.model.TokenUsageTotals
import com.david.deepdesk.core.model.UploadState
import com.david.deepdesk.feature.attachments.AttachmentManager
import com.david.deepdesk.feature.attachments.AttachmentTypeResolver
import com.david.deepdesk.feature.attachments.ImageCrop
import com.david.deepdesk.feature.chat.data.ChatRepository
import com.david.deepdesk.feature.chat.engine.ChatOrchestrator
import com.david.deepdesk.feature.chat.engine.ChatRequestLimit
import com.david.deepdesk.feature.chat.engine.MAX_DOCUMENT_REFERENCES
import com.david.deepdesk.feature.chat.engine.RequestFactory
import com.david.deepdesk.feature.chat.engine.WebToolHistory
import com.david.deepdesk.core.network.DeepSeekApiException
import com.david.deepdesk.core.ui.R as UiR
import com.david.deepdesk.feature.voice.recording.RecordingController
import com.david.deepdesk.feature.voice.tts.TtsPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** UI 消息：领域消息 + 块 + 分支版本信息 */
data class UiMessage(
    val message: ChatMessage,
    val blocks: List<ReplyBlock>,
    val versionCount: Int = 1,
    val versionIndex: Int = 0,
    val attachments: List<Attachment> = emptyList(),
)

data class ChatUiState(
    val sessionId: String? = null,
    val conversationTitle: String = "",
    val messages: List<UiMessage> = emptyList(),
    val draftText: String = "",
    val pendingAttachments: List<Attachment> = emptyList(),
    val thinkingEnabled: Boolean = false,
    val webSearchEnabled: Boolean = false,
    val streamingDraft: ChatOrchestrator.DraftUi? = null,
    val error: AppError? = null,
    /** 需要重试的失败消息（重新生成入口） */
    val retryableMessageId: String? = null,
    val ttsVoiceId: String = "stella",
    /** 自动朗读新回答（顶栏喇叭开关）；默认静音 */
    val autoReadEnabled: Boolean = false,
    val ocrResult: OcrResultState? = null,
    val ocrBusy: Boolean = false,
    val showRecognizedHint: Boolean = false,
    /** 语音识别提示（官方「未识别到内容」居中浮层） */
    val voiceHint: String? = null,
    val toastHint: String? = null,
    /** 打开会话时恢复滚动位置；epoch 变化表示需要应用一次 */
    val pendingScrollAnchorId: String? = null,
    val pendingScrollAnchorOffset: Int = 0,
    val pendingScrollAnchorEpoch: Long = 0,
    val hasApiKey: Boolean = false,
    val autoCollapseThinking: Boolean = true,
    val sessionUsage: TokenUsageTotals = TokenUsageTotals(),
    val showSessionUsageChip: Boolean = true,
    val professionalMode: Boolean = false,
    val modelId: String = ChatModels.FLASH,
    val reasoningEffort: String = ChatModels.EFFORT_HIGH,
    val catalogModels: List<ListedModel> = emptyList(),
    val catalogLoading: Boolean = false,
    val catalogError: String? = null,
) {
    val isStreaming: Boolean get() = streamingDraft != null
    /** 专业模式选了不支持视觉的模型时，不能再发图片。关闭专业模式时与现在一样，Flash 可以发图。 */
    val imagesEnabled: Boolean
        get() = !professionalMode || ChatModels.visionOf(catalogModels, modelId)
    val canSend: Boolean
        get() = !isStreaming &&
            (draftText.isNotBlank() || pendingAttachments.any { it.uploadState != UploadState.UPLOADING })
}

/** OCR 结果弹层状态（F14） */
data class OcrResultState(
    val fileName: String,
    val text: String,
    val pages: Int,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val services: ChatServices) : ViewModel() {

    private val _ui = MutableStateFlow(ChatUiState(hasApiKey = services.hasApiKey()))
    val ui: StateFlow<ChatUiState> = _ui

    val recording: StateFlow<RecordingController.State> = services.recordingController.state
    val tts: StateFlow<TtsPlayer.State> = services.ttsPlayer.state

    private var draftSaveJob: Job? = null
    private var asrJob: Job? = null
    private val sessionMutex = Mutex()
    private val reloadMutex = Mutex()
    private val sendMutex = Mutex()

    init {
        // 生成中的草稿 → UI（生成任务独立于 Composable 生命周期，flatMapLatest 防止收集器泄漏）
        viewModelScope.launch {
            var previousDraft: ChatOrchestrator.DraftUi? = null
            services.orchestrator.running.flatMapLatest { running ->
                if (running == null) flowOf(null)
                else running.draft.map { it }
            }.collect { draft ->
                val finished = previousDraft?.takeIf { draft == null }
                previousDraft = draft
                if (finished != null) {
                    // 保留流式回复，直到最终消息可以在同一次界面更新中替换它。
                    if (_ui.value.sessionId == finished.sessionId) {
                        try {
                            withContext(Dispatchers.IO) {
                                refreshMessages(finished.sessionId, finished.assistantMessageId)
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.e(TAG, "刷新生成结果失败", e)
                        }
                        clearStreamingDraft(finished.assistantMessageId)
                        readLastAssistant(finished)
                    }
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { services.balanceRepository.refresh() }
                    }
                } else {
                    _ui.update { state ->
                        state.copy(streamingDraft = draft?.takeIf { it.sessionId == state.sessionId })
                    }
                }
            }
        }
        // 录音状态观察：Processing → ASR；Recognized → 回填并发送（松手发送）
        viewModelScope.launch {
            services.recordingController.state.collect { state ->
                when (state) {
                    is RecordingController.State.Processing -> performAsr(state.file)
                    is RecordingController.State.Recognized -> {
                        val recognized = state.text
                        if (recognized.isBlank()) {
                            services.recordingController.cleanupTempFile()
                            _ui.value = _ui.value.copy(voiceHint = str(UiR.string.asr_empty))
                        } else {
                            val current = _ui.value.draftText
                            onDraftChange(if (current.isBlank()) recognized else "$current $recognized")
                            services.recordingController.cleanupTempFile()
                            send()
                        }
                    }
                    else -> Unit
                }
            }
        }
        // 深度思考 / 智能搜索等偏好：持续收集，冷启动与设置页改动都会同步到输入条
        viewModelScope.launch {
            services.settingsStore.settings.collect { settings ->
                _ui.update { state ->
                    state.copy(
                        thinkingEnabled = settings.thinkingEnabledByDefault,
                        webSearchEnabled = settings.webSearchEnabledByDefault,
                        ttsVoiceId = settings.voiceId,
                        autoReadEnabled = settings.autoReadEnabled,
                        autoCollapseThinking = settings.autoCollapseThinking,
                        showSessionUsageChip = settings.showSessionUsageChip,
                        professionalMode = settings.professionalMode,
                        modelId = settings.modelId,
                        reasoningEffort = settings.reasoningEffort,
                        hasApiKey = services.hasApiKey(),
                    )
                }
            }
        }
        viewModelScope.launch {
            _ui.map { it.sessionId }.distinctUntilChanged().flatMapLatest { id ->
                if (id.isNullOrBlank()) flowOf(TokenUsageTotals())
                else services.repository.observeSessionUsage(id)
            }.collect { usage ->
                _ui.update { it.copy(sessionUsage = usage) }
            }
        }
    }

    fun refreshApiKey() {
        _ui.value = _ui.value.copy(hasApiKey = services.hasApiKey())
    }

    // ------------------------------ 会话加载 ------------------------------

    fun loadSession(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 已经在看这个会话：保留未落库的草稿，只刷新滚动锚点与进行中的流
            if (_ui.value.sessionId == sessionId && _ui.value.messages.isNotEmpty()) {
                val anchor = services.repository.getScrollAnchor(sessionId)
                _ui.value = _ui.value.copy(
                    pendingScrollAnchorId = anchor?.anchorMessageId,
                    pendingScrollAnchorOffset = anchor?.anchorOffset ?: 0,
                    pendingScrollAnchorEpoch = _ui.value.pendingScrollAnchorEpoch + 1,
                    streamingDraft = liveDraftFor(sessionId) ?: _ui.value.streamingDraft,
                )
                return@launch
            }
            val data = services.repository.loadSession(sessionId)
            val draft = services.repository.getDraft(sessionId) ?: ""
            val allAttachments = services.repository.getAttachments(sessionId)
            val pending = allAttachments.filter { it.messageId == null && it.uploadState != UploadState.FAILED }
            if (data == null) {
                _ui.value = preferencesOnly(_ui.value)
                return@launch
            }
            val anchor = services.repository.getScrollAnchor(sessionId)
            _ui.value = _ui.value.copy(
                sessionId = sessionId,
                conversationTitle = data.conversation.title.ifBlank { str(UiR.string.new_chat) },
                messages = toUiMessages(data, allAttachments),
                draftText = draft,
                pendingAttachments = pending,
                streamingDraft = liveDraftFor(sessionId),
                error = null,
                retryableMessageId = null,
                pendingScrollAnchorId = anchor?.anchorMessageId,
                pendingScrollAnchorOffset = anchor?.anchorOffset ?: 0,
                pendingScrollAnchorEpoch = _ui.value.pendingScrollAnchorEpoch + 1,
                hasApiKey = services.hasApiKey(),
            )
        }
    }

    /** 换会话时保留输入栏偏好。专业模式不能在回到聊天页时被清掉。 */
    private fun preferencesOnly(prev: ChatUiState) = ChatUiState(
        thinkingEnabled = prev.thinkingEnabled,
        webSearchEnabled = prev.webSearchEnabled,
        ttsVoiceId = prev.ttsVoiceId,
        autoReadEnabled = prev.autoReadEnabled,
        autoCollapseThinking = prev.autoCollapseThinking,
        showSessionUsageChip = prev.showSessionUsageChip,
        professionalMode = prev.professionalMode,
        modelId = prev.modelId,
        reasoningEffort = prev.reasoningEffort,
        catalogModels = prev.catalogModels,
        hasApiKey = services.hasApiKey(),
    )

    private fun liveDraftFor(sessionId: String) =
        services.orchestrator.running.value
            ?.takeIf { it.sessionId == sessionId }
            ?.draft
            ?.value

    /**
     * 顶栏 / 侧栏新建。已经在空白新对话里时只提示，避免反复切页动画。
     * @return true 已切到新对话，调用方可以导航到 chat/new。
     */
    fun requestNewSession(): Boolean {
        val s = _ui.value
        if (s.messages.isEmpty() && !s.isStreaming) {
            _ui.value = s.copy(toastHint = str(UiR.string.already_in_new_chat))
            return false
        }
        newSession()
        return true
    }

    /** 新建对话：不立即落库（首条消息发送时才创建会话行，避免空会话） */
    fun newSession() {
        // 必须先停掉正在进行的生成：否则 orchestrator 的流式草稿回调会把旧会话内容
        // 写回刚清空的新会话，表现为「点了新建但内容又回来了」。
        if (_ui.value.isStreaming) services.orchestrator.stopGeneration()
        val prev = _ui.value
        val emptySessionId = prev.sessionId.takeIf {
            prev.messages.isEmpty() && !prev.isStreaming
        }
        val leftoverAttachments = prev.pendingAttachments
        _ui.value = preferencesOnly(prev).copy(
            conversationTitle = str(UiR.string.new_chat),
        )
        if (emptySessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                leftoverAttachments.forEach { att ->
                    runCatching { services.attachmentManager.deleteLocalFile(att) }
                }
                services.repository.delete(emptySessionId)
            }
        }
    }

    /** 首条消息或附件落库前确保已有会话行，避免新对话无法添加附件。 */
    private suspend fun ensureSessionId(): String = sessionMutex.withLock {
        _ui.value.sessionId?.let { return@withLock it }
        val conv = services.repository.createConversation()
        _ui.value = _ui.value.copy(
            sessionId = conv.id,
            conversationTitle = _ui.value.conversationTitle.ifBlank { str(UiR.string.new_chat) },
        )
        conv.id
    }

    private fun toUiMessages(
        data: ChatRepository.SessionData,
        attachments: List<Attachment> = emptyList(),
    ): List<UiMessage> {
        val attachmentsByMessage = attachments.groupBy { it.messageId }
        // 版本信息：同一父节点下同角色的兄弟数量与当前下标
        val allNodes = data.roots
        val siblingsByParent = allNodes.groupBy { it.message.parentId }

        fun versionInfo(msg: ChatMessage): Pair<Int, Int> {
            val siblings = siblingsByParent[msg.parentId]
                ?.filter { it.message.role == msg.role }
                ?.sortedBy { it.message.createdAt }
                .orEmpty()
            if (siblings.size <= 1) return 1 to 0
            val idx = siblings.indexOfFirst { it.message.id == msg.id }
            return siblings.size to idx
        }

        return data.activePath.map { msg ->
            val (count, index) = versionInfo(msg)
            UiMessage(
                message = msg,
                blocks = WebToolHistory.displayBlocks(data.blocksByMessageId[msg.id].orEmpty()),
                versionCount = count,
                versionIndex = index,
                attachments = attachmentsByMessage[msg.id].orEmpty(),
            )
        }
    }

    private fun collectDescendants(node: ChatRepository.Node): List<ChatRepository.Node> =
        node.children + node.children.flatMap { collectDescendants(it) }

    private fun reloadMessages() {
        val sessionId = _ui.value.sessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            refreshMessages(sessionId)
        }
    }

    private suspend fun refreshMessages(sessionId: String, finishedMessageId: String? = null) = reloadMutex.withLock {
        val data = services.repository.loadSession(sessionId) ?: return@withLock
        val messages = toUiMessages(data, services.repository.getAttachments(sessionId))
        _ui.update { state ->
            if (state.sessionId != sessionId) state else state.copy(
                conversationTitle = data.conversation.title.ifBlank { str(UiR.string.new_chat) },
                messages = messages,
                streamingDraft = state.streamingDraft?.takeUnless { it.assistantMessageId == finishedMessageId },
            )
        }
    }

    private fun clearStreamingDraft(messageId: String) {
        _ui.update { state ->
            if (state.streamingDraft?.assistantMessageId == messageId) state.copy(streamingDraft = null) else state
        }
    }

    // ------------------------------ 输入与草稿 ------------------------------

    fun onDraftChange(text: String) {
        _ui.update { it.copy(draftText = text) }
        val sessionId = _ui.value.sessionId ?: return
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400)
            services.repository.saveDraft(sessionId, text)
        }
    }

    /** 首页建议卡片：填入草稿并立即发送，对齐官方 App 欢迎区。 */
    fun sendSuggestion(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        onDraftChange(trimmed)
        send()
    }

    fun setThinking(enabled: Boolean) {
        _ui.value = _ui.value.copy(thinkingEnabled = enabled)
        viewModelScope.launch { services.settingsStore.setDefaults(thinking = enabled) }
    }

    fun setWebSearch(enabled: Boolean) {
        _ui.value = _ui.value.copy(webSearchEnabled = enabled)
        viewModelScope.launch { services.settingsStore.setDefaults(search = enabled) }
    }

    /** 专业模式滑块。0 关闭思考；1/2/3 对应 low/high/max，并打开思考。 */
    fun setThinkingLevel(level: Int) {
        val effort = ChatModels.effortForLevel(level.coerceIn(0, 3))
        _ui.value = _ui.value.copy(
            thinkingEnabled = effort != null,
            reasoningEffort = effort ?: _ui.value.reasoningEffort,
        )
        viewModelScope.launch {
            services.settingsStore.setThinkingLevel(thinking = effort != null, effort = effort)
        }
    }

    fun setChatModel(modelId: String) {
        val model = ChatModels.sanitize(modelId)
        val visionMessage = str(UiR.string.model_no_vision)
        val blocksImages = !ChatModels.visionOf(_ui.value.catalogModels, model) &&
            _ui.value.pendingAttachments.any { it.isImage }
        val error = when {
            blocksImages -> AppError(AppError.Kind.LOCAL, visionMessage)
            _ui.value.error?.message == visionMessage -> null
            else -> _ui.value.error
        }
        _ui.value = _ui.value.copy(modelId = model, error = error)
        viewModelScope.launch { services.settingsStore.setModelId(model) }
    }

    /** GET /models，专业模式模型浮窗用。失败时仍给出 Flash / Pro，方便切换。 */
    fun loadCatalogModels() {
        if (_ui.value.catalogLoading) return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(catalogLoading = true, catalogError = null)
            try {
                val listed = services.restApi.listModels().data.mapNotNull { it.toListedModel() }
                val list = ChatModels.mergeCatalog(listed, _ui.value.modelId)
                _ui.value = _ui.value.copy(catalogLoading = false, catalogModels = list, catalogError = null)
            } catch (e: Exception) {
                AppLog.w(TAG, "加载模型列表失败", e)
                val message = when (e) {
                    is DeepSeekApiException -> e.toAppError().message
                    else -> str(UiR.string.network_error, e.message ?: "")
                }
                _ui.value = _ui.value.copy(
                    catalogLoading = false,
                    catalogModels = ChatModels.mergeCatalog(_ui.value.catalogModels, _ui.value.modelId),
                    catalogError = message,
                )
            }
        }
    }

    private fun currentModel(): String =
        if (_ui.value.professionalMode) ChatModels.sanitize(_ui.value.modelId) else RequestFactory.MODEL

    private fun currentOptions(
        thinkingEnabled: Boolean,
        webSearchEnabled: Boolean,
        readUrlEnabled: Boolean,
        titleEnabled: Boolean,
    ) = RequestFactory.Options(
        thinkingEnabled = thinkingEnabled,
        webSearchEnabled = webSearchEnabled,
        readUrlEnabled = readUrlEnabled,
        titleEnabled = titleEnabled,
        model = currentModel(),
        reasoningEffort = if (_ui.value.professionalMode && thinkingEnabled) {
            ChatModels.sanitizeEffort(_ui.value.reasoningEffort)
        } else {
            null
        },
    )

    private fun rejectImages(imageFileIds: Set<String>): Boolean {
        if (imageFileIds.isEmpty() || _ui.value.imagesEnabled) return false
        _ui.value = _ui.value.copy(error = AppError(AppError.Kind.LOCAL, str(UiR.string.model_no_vision)))
        return true
    }

    // ------------------------------ 发送 / 停止 ------------------------------

    /** 发送：上传未上传附件 → 插入用户消息 → 绑定附件 → 发起生成（F02） */
    fun send() {
        val state = _ui.value
        if (!state.canSend) return
        if (!services.hasApiKey()) {
            _ui.value = state.copy(error = AppError(AppError.Kind.AUTH, str(UiR.string.need_api_key)))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (!sendMutex.tryLock()) return@launch
            var sendingSessionId = state.sessionId
            try {
                if (_ui.value.sessionId != state.sessionId) return@launch
                check(services.orchestrator.running.value == null) { "已有生成任务进行中" }
                // 首条消息或附件：先创建会话
                val sessionId = state.sessionId ?: ensureSessionId()
                sendingSessionId = sessionId
                if (state.pendingAttachments.any { it.isImage } && !state.imagesEnabled) {
                    _ui.value = _ui.value.copy(error = AppError(AppError.Kind.LOCAL, str(UiR.string.model_no_vision)))
                    return@launch
                }
                val (existingPath, _) = services.repository.activePathWithBlocks(sessionId)
                val existingRefs = attachmentsByMessage(sessionId, existingPath)
                if (rejectDocuments(existingRefs, state.pendingAttachments.count { !it.isImage })) return@launch
                if (rejectImages(existingRefs.imageFileIds)) return@launch
                // 1. 上传附件（未 UPLOADED 的失败附件不入请求）
                val uploadedIds = mutableListOf<String>()
                val newImageIds = mutableSetOf<String>()
                state.pendingAttachments.forEach { att ->
                    val fileId = when (att.uploadState) {
                        UploadState.UPLOADED -> att.serverFileId!!
                        UploadState.LOCAL, UploadState.FAILED -> uploadAttachment(att)
                        UploadState.UPLOADING -> {
                            _ui.value = _ui.value.copy(
                                error = AppError(AppError.Kind.LOCAL, "附件仍在上传中，请稍候")
                            )
                            return@launch
                        }
                    }
                    uploadedIds += fileId
                    if (att.isImage) newImageIds += fileId
                }

                val text = state.draftText.trim()
                val now = services.time.nowMillis()

                // 2. 插入用户消息（含文本块）
                val isFirstTurn = existingPath.isEmpty()
                val userMessage = ChatMessage(
                    id = services.time.newId(),
                    sessionId = sessionId,
                    role = "user",
                    parentId = existingPath.lastOrNull()?.id,
                    createdAt = now,
                )
                val userBlocks = if (text.isNotBlank()) listOf<ReplyBlock>(ReplyBlock.Text(text)) else emptyList()
                services.repository.insertMessage(userMessage, userBlocks)
                services.repository.bindAttachmentsToMessage(
                    state.pendingAttachments.map { it.id },
                    userMessage.id,
                )
                val current = _ui.value
                if (current.sessionId != sessionId || current.draftText == state.draftText) {
                    services.repository.saveDraft(sessionId, "")
                }

                // 3. 用同一次会话读取更新列表并组装请求，避免长对话重复加载。
                val sentAttachmentIds = state.pendingAttachments.map { it.id }.toSet()
                val (data, attachments) = reloadMutex.withLock {
                    val data = checkNotNull(services.repository.loadSession(sessionId))
                    val attachments = services.repository.getAttachments(sessionId)
                    val messages = toUiMessages(data, attachments)
                    _ui.update { currentState ->
                        if (currentState.sessionId != sessionId) currentState else currentState.copy(
                            messages = messages,
                            draftText = currentState.draftText.takeUnless { it == state.draftText }.orEmpty(),
                            pendingAttachments = currentState.pendingAttachments.filterNot { it.id in sentAttachmentIds },
                            error = null,
                            retryableMessageId = null,
                        )
                    }
                    data to attachments
                }
                val history = data.activePath.takeWhile { it.id != userMessage.id }
                val refs = attachmentRefs(attachments, history)
                val request = RequestFactory.build(
                    history = history,
                    blocksByMessageId = data.blocksByMessageId,
                    attachmentsByMessageId = refs.byMessage,
                    newMessageText = text,
                    newMessageFileIds = uploadedIds,
                    imageFileIds = refs.imageFileIds + newImageIds,
                    options = currentOptions(
                        thinkingEnabled = state.thinkingEnabled,
                        webSearchEnabled = state.webSearchEnabled,
                        readUrlEnabled = RequestFactory.containsUrl(text) || state.webSearchEnabled,
                        titleEnabled = isFirstTurn,
                    ),
                )

                services.orchestrator.generate(
                    sessionId = sessionId,
                    parentMessageId = userMessage.id,
                    request = request,
                    model = currentModel(),
                    thinkingEnabled = state.thinkingEnabled,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = AttachmentManager.toAppError(e)
                _ui.update {
                    if (it.sessionId != sendingSessionId) it else it.copy(
                        error = if (error.kind == AppError.Kind.UNKNOWN) AppError(AppError.Kind.LOCAL, "发送失败：${e.message}") else error,
                    )
                }
            } finally {
                sendMutex.unlock()
            }
        }
    }

    private data class AttachmentRefs(
        val byMessage: Map<String, List<String>>,
        val imageFileIds: Set<String>,
    ) {
        val documentCount: Int
            get() = byMessage.values.sumOf { ids -> ids.count { it !in imageFileIds } }
    }

    private fun rejectDocuments(refs: AttachmentRefs, newDocuments: Int = 0): Boolean {
        val count = refs.documentCount + newDocuments
        if (count <= MAX_DOCUMENT_REFERENCES) return false
        val canStartOver = refs.documentCount > 0 && newDocuments <= MAX_DOCUMENT_REFERENCES
        val error = AppError(
            if (canStartOver) AppError.Kind.CONTEXT_LIMIT else AppError.Kind.LOCAL,
            services.appContext.getString(UiR.string.document_reference_limit, MAX_DOCUMENT_REFERENCES, count),
        )
        _ui.update { it.copy(error = error) }
        return true
    }

    /** 一次会话级查询得到“消息 → 已上传 file_id 列表”，并标出哪些是图片。 */
    private suspend fun attachmentsByMessage(sessionId: String, history: List<ChatMessage>): AttachmentRefs =
        attachmentRefs(services.repository.getAttachments(sessionId), history)

    private fun attachmentRefs(attachments: List<Attachment>, history: List<ChatMessage>): AttachmentRefs {
        val historyIds = history.filter { it.role == "user" }.map { it.id }.toSet()
        val included = attachments
            .filter { it.messageId != null && it.serverFileId != null && it.messageId in historyIds }
        return AttachmentRefs(
            byMessage = included.groupBy({ it.messageId!! }, { it.serverFileId!! }),
            imageFileIds = included.filter { it.isImage }.mapNotNull { it.serverFileId }.toSet(),
        )
    }

    private suspend fun uploadAttachment(att: Attachment): String {
        services.repository.updateAttachmentUpload(att.id, UploadState.UPLOADING, null, 0, null)
        updatePendingAttachment(att.id) { it.copy(uploadState = UploadState.UPLOADING, progressPercent = 0) }
        return try {
            val fileId = withContext(Dispatchers.IO) {
                services.attachmentManager.uploadBlocking(att) { progress ->
                    updatePendingAttachment(att.id) { it.copy(progressPercent = progress) }
                }
            }
            services.repository.updateAttachmentUpload(att.id, UploadState.UPLOADED, fileId, 100, null)
            updatePendingAttachment(att.id) { it.copy(uploadState = UploadState.UPLOADED, serverFileId = fileId, progressPercent = 100) }
            fileId
        } catch (e: Exception) {
            val err = AttachmentManager.toAppError(e)
            services.repository.updateAttachmentUpload(att.id, UploadState.FAILED, null, 0, err.message)
            updatePendingAttachment(att.id) { it.copy(uploadState = UploadState.FAILED, errorMessage = err.message) }
            throw err
        }
    }

    private fun updatePendingAttachment(id: String, transform: (Attachment) -> Attachment) {
        _ui.value = _ui.value.copy(
            pendingAttachments = _ui.value.pendingAttachments.map { if (it.id == id) transform(it) else it }
        )
    }

    fun stopGeneration() = services.orchestrator.stopGeneration()

    fun retryLast() {
        val failedId = _ui.value.retryableMessageId ?: return
        regenerate(failedId)
    }

    fun dismissError() {
        _ui.value = _ui.value.copy(error = null)
    }

    fun dismissVoiceHint() {
        _ui.value = _ui.value.copy(voiceHint = null, toastHint = null)
    }

    // ------------------------------ 分支操作（F09） ------------------------------

    /** 重新生成：为同一父消息新增一个 assistant 兄弟分支 */
    fun regenerate(assistantMessageId: String) {
        val sessionId = _ui.value.sessionId ?: return
        if (services.orchestrator.isGenerating(sessionId)) return
        if (!services.hasApiKey()) {
            _ui.value = _ui.value.copy(error = AppError(AppError.Kind.AUTH, "请先在设置中配置 DeepSeek API Key"))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val data = services.repository.loadSession(sessionId) ?: return@launch
            val target = data.roots.flatMap { collectDescendants(it) }.firstOrNull { it.message.id == assistantMessageId }
                ?: data.roots.firstOrNull { it.message.id == assistantMessageId }
                ?: return@launch
            val parentId = target.message.parentId
            val (path, blocks) = services.repository.activePathWithBlocks(sessionId)

            // 历史截断到父消息（含），不追加新用户消息
            val history = if (parentId == null) emptyList() else {
                val parentIndex = path.indexOfFirst { it.id == parentId }
                if (parentIndex < 0) return@launch
                path.take(parentIndex + 1)
            }
            val attByMsg = attachmentsByMessage(sessionId, history)
            if (rejectDocuments(attByMsg) || rejectImages(attByMsg.imageFileIds)) return@launch
            val needsRepair = data.blocksByMessageId[assistantMessageId].orEmpty()
                .any { ChatRequestLimit.fromBlock(it) == ChatRequestLimit.FILE_UNAVAILABLE }
            if (!sendMutex.tryLock()) return@launch
            try {
                if (_ui.value.sessionId != sessionId || services.orchestrator.running.value != null) return@launch
                val refs = if (needsRepair) {
                    repairFiles(sessionId, attByMsg.byMessage.values.flatten().toSet())
                    attachmentsByMessage(sessionId, history)
                } else {
                    attByMsg
                }
                if (_ui.value.sessionId != sessionId || services.orchestrator.running.value != null) return@launch
                val webSearchEnabled = _ui.value.webSearchEnabled
                val request = RequestFactory.build(
                    history = history,
                    blocksByMessageId = blocks,
                    attachmentsByMessageId = refs.byMessage,
                    imageFileIds = refs.imageFileIds,
                    newMessageText = "",
                    newMessageFileIds = emptyList(),
                    options = currentOptions(
                        thinkingEnabled = _ui.value.thinkingEnabled,
                        webSearchEnabled = webSearchEnabled,
                        readUrlEnabled = webSearchEnabled || RequestFactory.lastUserMessageContainsUrl(history, blocks),
                        titleEnabled = false,
                    ),
                    appendUserMessage = false,
                )
                services.orchestrator.generate(
                    sessionId = sessionId,
                    parentMessageId = parentId,
                    request = request,
                    model = currentModel(),
                    thinkingEnabled = _ui.value.thinkingEnabled,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { state ->
                    if (state.sessionId != sessionId) state else state.copy(error = AttachmentManager.toAppError(e))
                }
            } finally {
                sendMutex.unlock()
            }
        }
    }

    private suspend fun repairFiles(sessionId: String, referencedIds: Set<String>) {
        val missingIds = referencedIds.filterNot(services.filesClient::isAvailableBlocking).toSet()
        if (missingIds.isEmpty()) return
        val availableCopies = services.repository.getAttachments(sessionId)
            .filter { it.serverFileId in missingIds && it.localPath?.let { path -> File(path).isFile } == true }
            .associateBy { it.serverFileId }
        if (!availableCopies.keys.containsAll(missingIds)) {
            throw AppError(AppError.Kind.CONTEXT_LIMIT, str(UiR.string.file_unavailable_no_local_copy))
        }
        // Reuse the new ID for repeated references to the same file.
        for (oldId in missingIds) {
            val fileId = services.attachmentManager.uploadBlocking(availableCopies.getValue(oldId)) {}
            services.repository.replaceServerFileId(sessionId, oldId, fileId)
        }
    }

    /** 手动停止后接着写同一条回复，不另开一条。 */
    fun continueGeneration(assistantMessageId: String) {
        val sessionId = _ui.value.sessionId ?: return
        if (services.orchestrator.isGenerating(sessionId)) return
        if (!services.hasApiKey()) {
            _ui.value = _ui.value.copy(error = AppError(AppError.Kind.AUTH, str(UiR.string.need_api_key)))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val data = services.repository.loadSession(sessionId) ?: return@launch
            val target = data.roots.flatMap { collectDescendants(it) }
                .firstOrNull { it.message.id == assistantMessageId }
                ?: data.roots.firstOrNull { it.message.id == assistantMessageId }
                ?: return@launch
            if (target.message.status != ReplyStatus.STOPPED) return@launch
            val parentId = target.message.parentId
            val (path, blocks) = services.repository.activePathWithBlocks(sessionId)
            val history = if (parentId == null) {
                emptyList()
            } else {
                val parent = path.firstOrNull { it.id == parentId } ?: return@launch
                path.takeWhile { it.id != parentId } + parent
            }
            val prefix = blocks[assistantMessageId].orEmpty()
            val prefill = prefix.filterIsInstance<ReplyBlock.Text>().joinToString("\n") { it.text }.trim()
            // 已有正文时关掉思考：思考开启时，纯文本的 assistant 前缀会被接口拒绝。
            val continueThinking = prefill.isBlank() && _ui.value.thinkingEnabled
            val attByMsg = attachmentsByMessage(sessionId, history)
            if (rejectDocuments(attByMsg) || rejectImages(attByMsg.imageFileIds)) return@launch
            val request = RequestFactory.build(
                history = history,
                blocksByMessageId = blocks,
                attachmentsByMessageId = attByMsg.byMessage,
                imageFileIds = attByMsg.imageFileIds,
                newMessageText = "",
                newMessageFileIds = emptyList(),
                options = currentOptions(
                    thinkingEnabled = continueThinking,
                    webSearchEnabled = _ui.value.webSearchEnabled,
                    readUrlEnabled = _ui.value.webSearchEnabled,
                    titleEnabled = false,
                ),
                appendUserMessage = false,
                assistantPrefill = prefill.ifBlank { null },
            )
            services.orchestrator.generate(
                sessionId = sessionId,
                parentMessageId = parentId,
                request = request,
                model = currentModel(),
                thinkingEnabled = continueThinking,
                resumeMessageId = assistantMessageId,
                prefixBlocks = prefix,
            )
        }
    }

    /** 编辑历史提问：创建兄弟分支并重新生成（编辑旧消息不抹掉旧答案 §6.2） */
    fun editMessage(userMessageId: String, newText: String) {
        val sessionId = _ui.value.sessionId ?: return
        if (services.orchestrator.isGenerating(sessionId)) return
        if (!services.hasApiKey()) {
            _ui.value = _ui.value.copy(error = AppError(AppError.Kind.AUTH, "请先在设置中配置 DeepSeek API Key"))
            return
        }
        if (newText.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val data = services.repository.loadSession(sessionId) ?: return@launch
            val node = data.roots.flatMap { collectDescendants(it) }.firstOrNull { it.message.id == userMessageId }
                ?: data.roots.firstOrNull { it.message.id == userMessageId }
                ?: return@launch

            val (pathBefore, _) = services.repository.activePathWithBlocks(sessionId)
            val historyBefore = pathBefore.takeWhile { it.id != userMessageId }
            val refsBefore = attachmentsByMessage(sessionId, historyBefore)
            if (rejectDocuments(refsBefore) || rejectImages(refsBefore.imageFileIds)) return@launch

            val newUser = ChatMessage(
                id = services.time.newId(),
                sessionId = sessionId,
                role = "user",
                parentId = node.message.parentId,
                createdAt = services.time.nowMillis(),
                edited = true,
            )
            services.repository.insertMessage(newUser, listOf(ReplyBlock.Text(newText.trim())))

            val (path, blocks) = services.repository.activePathWithBlocks(sessionId)
            val history = path.dropLast(1)
            val attByMsg = attachmentsByMessage(sessionId, history)
            val request = RequestFactory.build(
                history = history,
                blocksByMessageId = blocks,
                attachmentsByMessageId = attByMsg.byMessage,
                imageFileIds = attByMsg.imageFileIds,
                newMessageText = newText.trim(),
                newMessageFileIds = emptyList(),
                options = currentOptions(
                    thinkingEnabled = _ui.value.thinkingEnabled,
                    webSearchEnabled = _ui.value.webSearchEnabled,
                    readUrlEnabled = RequestFactory.containsUrl(newText) || _ui.value.webSearchEnabled,
                    titleEnabled = false,
                ),
            )
            reloadMessages()
            services.orchestrator.generate(
                sessionId = sessionId,
                parentMessageId = newUser.id,
                request = request,
                model = currentModel(),
                thinkingEnabled = _ui.value.thinkingEnabled,
            )
        }
    }

    /** 切换版本（‹ ›）：父消息 selectedChildId 指向兄弟节点 */
    fun switchVersion(messageId: String, direction: Int) {
        val sessionId = _ui.value.sessionId ?: return
        if (services.orchestrator.isGenerating(sessionId)) return
        viewModelScope.launch(Dispatchers.IO) {
            val data = services.repository.loadSession(sessionId) ?: return@launch
            val all = data.roots
            val node = all.firstOrNull { it.message.id == messageId } ?: return@launch
            val siblings = siblingsByParent(all, node.message.parentId, node.message.role)
            if (siblings.size <= 1) return@launch
            val idx = siblings.indexOfFirst { it.message.id == messageId }
            val next = (idx + direction).coerceIn(0, siblings.size - 1)
            if (next == idx) return@launch
            services.repository.selectChild(node.message.parentId, siblings[next].message.id, sessionId)
            reloadMessages()
        }
    }

    private fun siblingsByParent(
        all: List<ChatRepository.Node>,
        parentId: String?,
        role: String,
    ): List<ChatRepository.Node> =
        all.filter { it.message.parentId == parentId && it.message.role == role }.sortedBy { it.message.createdAt }

    // ------------------------------ 附件（F07/F08） ------------------------------

    data class SharedDraft(val sessionId: String?, val hadFailures: Boolean)

    /** 系统分享使用独立的本地草稿。只有用户发送后才走常规上传流程。 */
    suspend fun importSharedDraft(uris: List<Uri>, text: String): SharedDraft = withContext(Dispatchers.IO) {
        if (uris.isEmpty() && text.isBlank()) return@withContext SharedDraft(null, false)
        val sessionId = try {
            services.repository.createConversation().id
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withContext SharedDraft(null, true)
        }
        val imported = mutableListOf<Attachment>()
        var hadFailures = false
        var keepDraft = false
        try {
            for (uri in uris) {
                ensureActive()
                var attachment: Attachment? = null
                try {
                    attachment = services.attachmentManager.importBlocking(uri, sessionId)
                    services.repository.insertAttachment(attachment)
                    imported += attachment
                } catch (e: CancellationException) {
                    attachment?.let(services.attachmentManager::deleteLocalFile)
                    throw e
                } catch (_: Exception) {
                    attachment?.let(services.attachmentManager::deleteLocalFile)
                    hadFailures = true
                }
            }
            if (imported.isEmpty() && text.isBlank()) return@withContext SharedDraft(null, true)
            if (text.isNotBlank()) services.repository.saveDraft(sessionId, text)
            val current = _ui.value
            if (current.sessionId == null && current.draftText.isNotBlank()) {
                services.repository.saveDraft(ensureSessionId(), current.draftText)
            }
            keepDraft = true
            SharedDraft(sessionId, hadFailures)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SharedDraft(null, true)
        } finally {
            if (!keepDraft) withContext(NonCancellable) {
                imported.forEach(services.attachmentManager::deleteLocalFile)
                runCatching { services.repository.delete(sessionId) }
            }
        }
    }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessionId = ensureSessionId()
                val attachment = services.attachmentManager.importBlocking(uri, sessionId)
                if (attachment.isImage && !_ui.value.imagesEnabled) {
                    services.attachmentManager.deleteLocalFile(attachment)
                    _ui.value = _ui.value.copy(error = AppError(AppError.Kind.LOCAL, str(UiR.string.model_no_vision)))
                    return@launch
                }
                services.repository.insertAttachment(attachment)
                _ui.value = _ui.value.copy(
                    pendingAttachments = _ui.value.pendingAttachments + attachment
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = AttachmentManager.toAppError(e))
            }
        }
    }

    suspend fun useImageRegion(attachmentId: String, crop: ImageCrop): String? = withContext(Dispatchers.IO) {
        if (!sendMutex.tryLock()) return@withContext null
        try {
            val original = services.repository.getAttachment(attachmentId)
                ?.takeIf { it.isImage } ?: return@withContext null
            val pending = original.messageId == null
            if (pending && original.sessionId != _ui.value.sessionId) return@withContext null

            // A crop from history starts a fresh draft so the full image isn't included in its context.
            val sessionId = if (pending) original.sessionId else services.repository.createConversation().id
            var cropped: Attachment? = null
            var committed = false
            try {
                val replacement = services.attachmentManager.cropImageBlocking(original, crop, sessionId)
                cropped = replacement
                ensureActive()
                withContext(NonCancellable) {
                    committed = if (pending) {
                        services.repository.replacePendingAttachment(original.id, replacement)
                    } else {
                        services.repository.insertAttachment(replacement)
                        true
                    }
                    if (committed && pending) {
                        _ui.update { state ->
                            if (state.sessionId != sessionId) state else state.copy(
                                pendingAttachments = state.pendingAttachments.map { attachment ->
                                    if (attachment.id == original.id) replacement else attachment
                                },
                            )
                        }
                        services.attachmentManager.deleteLocalFile(original)
                    }
                }
                sessionId.takeIf { committed }
            } finally {
                if (!committed) withContext(NonCancellable) {
                    cropped?.let(services.attachmentManager::deleteLocalFile)
                    if (!pending) runCatching { services.repository.delete(sessionId) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            sendMutex.unlock()
        }
    }

    fun removeAttachment(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            services.repository.getAttachment(id)?.let {
                services.attachmentManager.deleteLocalFile(it)
            }
            services.repository.deleteAttachment(id)
            _ui.value = _ui.value.copy(
                pendingAttachments = _ui.value.pendingAttachments.filter { it.id != id }
            )
        }
    }

    fun retryAttachmentUpload(id: String) {
        val att = _ui.value.pendingAttachments.firstOrNull { it.id == id } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                uploadAttachment(att)
            } catch (_: Exception) {
                // 状态已在 uploadAttachment 中更新
            }
        }
    }

    // ------------------------------ OCR（F14） ------------------------------

    /** 附件“提取文字”：显式触发，独立于对话上传 */
    fun extractText(attachmentId: String) {
        if (_ui.value.ocrBusy) return
        viewModelScope.launch(Dispatchers.IO) {
            val att = services.repository.getAttachment(attachmentId)
                ?: _ui.value.pendingAttachments.firstOrNull { it.id == attachmentId }
                ?: return@launch
            val file = att.localPath?.let(::File)
            if (file == null || !file.exists()) {
                _ui.value = _ui.value.copy(error = AppError(AppError.Kind.LOCAL, "本地文件不存在"))
                return@launch
            }
            _ui.value = _ui.value.copy(ocrBusy = true, error = null)
            try {
                val result = services.toolsClient.ocrBlocking(file, att.mimeType, att.displayName)
                _ui.value = _ui.value.copy(
                    ocrBusy = false,
                    ocrResult = OcrResultState(
                        fileName = att.displayName,
                        text = result.plainText.ifBlank { str(UiR.string.ocr_empty) },
                        pages = result.pages.size,
                    ),
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(ocrBusy = false, error = AttachmentManager.toAppError(e))
            }
        }
    }

    fun dismissOcr() {
        _ui.value = _ui.value.copy(ocrResult = null)
    }

    // ------------------------------ 语音输入（F11） ------------------------------

    fun onMicClick(context: android.content.Context, hasPermission: Boolean) {
        if (!hasPermission) {
            // UI 层负责发起权限请求；此处仅标记状态
            return
        }
        services.recordingController.start(context)
    }

    /** 已有录音权限时预热 MIC，避免进程第一次按住说话录到空 WAV。 */
    fun warmupMic() {
        services.recordingController.warmup()
    }

    fun finishRecording(): File? = services.recordingController.finish()

    fun cancelRecording() {
        asrJob?.cancel()
        asrJob = null
        services.asrExecutor.cancelActive()
        services.recordingController.cancel()
    }

    fun retryAsr() {
        val state = services.recordingController.state.value
        val file = (state as? RecordingController.State.Error)?.file ?: return
        services.recordingController.beginProcessing(file)
    }

    fun dismissAsrError() {
        services.recordingController.cleanupTempFile()
    }

    private fun performAsr(file: File) {
        if (asrJob?.isActive == true) return
        asrJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = services.asrExecutor.recognize(file) ?: return@launch
                val stillWaiting = services.recordingController.state.value.let {
                    it is RecordingController.State.Processing && it.file == file
                }
                if (!stillWaiting) return@launch
                services.recordingController.notifyRecognized(result.text, result.durationMs)
            } catch (e: Exception) {
                val stillWaiting = services.recordingController.state.value.let {
                    it is RecordingController.State.Processing && it.file == file
                }
                if (!stillWaiting) return@launch
                val err = com.david.deepdesk.feature.voice.asr.AsrExecutor.toAppError(e)
                services.recordingController.cleanupTempFile()
                val hint = com.david.deepdesk.feature.voice.asr.AsrExecutor.userFacingHint(err)
                _ui.value = _ui.value.copy(
                    voiceHint = if (hint == "未识别到内容") str(UiR.string.asr_empty) else hint,
                )
            }
        }
    }

    // ------------------------------ 朗读（F12） ------------------------------

    fun toggleTts(messageId: String) {
        val uiMsg = _ui.value.messages.firstOrNull { it.message.id == messageId } ?: return
        val text = uiMsg.blocks.filterIsInstance<ReplyBlock.Text>().joinToString("\n") { it.text }
        if (text.isBlank()) return
        services.ttsPlayer.toggle(messageId, text, _ui.value.ttsVoiceId)
    }

    /** 顶栏喇叭开关：切换自动朗读偏好并持久化；关闭时立即停止当前朗读 */
    fun setAutoRead(enabled: Boolean) {
        _ui.value = _ui.value.copy(autoReadEnabled = enabled)
        if (!enabled) services.ttsPlayer.stop()
        viewModelScope.launch(Dispatchers.IO) {
            services.settingsStore.setAutoRead(enabled)
        }
    }

    /** 生成完成后自动朗读刚载入的最终回复（§3.5）。 */
    private fun readLastAssistant(finished: ChatOrchestrator.DraftUi) {
        val state = _ui.value
        if (!state.autoReadEnabled || state.sessionId != finished.sessionId) return
        val reply = state.messages.firstOrNull { it.message.id == finished.assistantMessageId } ?: return
        val text = reply.blocks.filterIsInstance<ReplyBlock.Text>().joinToString("\n") { it.text }
        if (text.isNotBlank()) {
            services.ttsPlayer.play(reply.message.id, text, state.ttsVoiceId)
        }
    }

    /** 音色页滑到另一张卡片时，用公开 TTS 接口读一句。 */
    fun previewVoice(voiceId: String) {
        if (!services.hasApiKey()) return
        val sample = when (voiceId) {
            "tide" -> str(UiR.string.voice_sample_tide)
            "stella" -> str(UiR.string.voice_sample_stella)
            else -> return
        }
        services.ttsPlayer.play("voice-preview", sample, voiceId)
    }

    fun onVoiceChanged(voiceId: String) {
        _ui.value = _ui.value.copy(ttsVoiceId = voiceId)
        services.ttsPlayer.onVoiceChanged()
    }

    // ------------------------------ 会话管理 ------------------------------

    fun renameSession(title: String) {
        val sessionId = _ui.value.sessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            services.repository.rename(sessionId, title)
            reloadMessages()
        }
    }

    fun saveScrollAnchor(messageId: String?, offset: Int) {
        val sessionId = _ui.value.sessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            services.repository.saveScrollAnchor(sessionId, messageId, offset)
        }
    }

    override fun onCleared() {
        // 页面销毁不泄漏音频资源；生成任务由 Orchestrator 挂靠应用级 scope 继续
        services.ttsPlayer.stop()
        services.recordingController.release()
        super.onCleared()
    }

    private fun str(id: Int, vararg args: Any): String =
        services.appContext.getString(id, *args)

    companion object {
        private const val TAG = "ChatViewModel"
    }
}
