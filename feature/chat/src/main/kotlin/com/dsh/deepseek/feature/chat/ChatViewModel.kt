package com.dsh.deepseek.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.deepseek.core.common.AppError
import com.dsh.deepseek.core.common.AppLog
import com.dsh.deepseek.core.model.ApiProtocol
import com.dsh.deepseek.core.model.Attachment
import com.dsh.deepseek.core.model.ChatMessage
import com.dsh.deepseek.core.model.ReplyBlock
import com.dsh.deepseek.core.model.ReplyStatus
import com.dsh.deepseek.core.model.TokenUsageTotals
import com.dsh.deepseek.core.model.UploadState
import com.dsh.deepseek.feature.attachments.AttachmentManager
import com.dsh.deepseek.feature.attachments.AttachmentTypeResolver
import com.dsh.deepseek.feature.chat.data.ChatRepository
import com.dsh.deepseek.feature.chat.engine.ChatOrchestrator
import com.dsh.deepseek.feature.chat.engine.RequestFactory
import com.dsh.deepseek.core.ui.R as UiR
import com.dsh.deepseek.feature.voice.recording.RecordingController
import com.dsh.deepseek.feature.voice.tts.TtsPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
    /** 打开会话时恢复滚动位置；epoch 变化表示需要应用一次 */
    val pendingScrollAnchorId: String? = null,
    val pendingScrollAnchorOffset: Int = 0,
    val pendingScrollAnchorEpoch: Long = 0,
    val hasApiKey: Boolean = false,
    val autoCollapseThinking: Boolean = true,
    val sessionUsage: TokenUsageTotals = TokenUsageTotals(),
    val showSessionUsageChip: Boolean = true,
) {
    val isStreaming: Boolean get() = streamingDraft != null
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
    private var hadStreamingDraft = false
    private val sessionMutex = Mutex()

    init {
        // 生成中的草稿 → UI（生成任务独立于 Composable 生命周期，flatMapLatest 防止收集器泄漏）
        viewModelScope.launch {
            services.orchestrator.running.flatMapLatest { running ->
                if (running == null) flowOf(null)
                else running.draft.map { it }
            }.collect { draft ->
                val forThisSession = draft != null && draft.sessionId == _ui.value.sessionId
                _ui.value = _ui.value.copy(
                    streamingDraft = if (forThisSession) draft else null
                )
                // 生成结束：重新加载以获得最终状态与标题
                if (hadStreamingDraft && draft == null) {
                    reloadMessages()
                    if (_ui.value.autoReadEnabled) readLastAssistant()
                    viewModelScope.launch(Dispatchers.IO) {
                        runCatching { services.balanceRepository.refresh() }
                    }
                }
                hadStreamingDraft = draft != null
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
                _ui.value = _ui.value.copy(
                    thinkingEnabled = settings.thinkingEnabledByDefault,
                    webSearchEnabled = settings.webSearchEnabledByDefault,
                    ttsVoiceId = settings.voiceId,
                    autoReadEnabled = settings.autoReadEnabled,
                    autoCollapseThinking = settings.autoCollapseThinking,
                    showSessionUsageChip = settings.showSessionUsageChip,
                    hasApiKey = services.hasApiKey(),
                )
            }
        }
        viewModelScope.launch {
            _ui.map { it.sessionId }.distinctUntilChanged().flatMapLatest { id ->
                if (id.isNullOrBlank()) flowOf(TokenUsageTotals())
                else services.repository.observeSessionUsage(id)
            }.collect { usage ->
                _ui.value = _ui.value.copy(sessionUsage = usage)
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
                val prev = _ui.value
                _ui.value = ChatUiState(
                    thinkingEnabled = prev.thinkingEnabled,
                    webSearchEnabled = prev.webSearchEnabled,
                    ttsVoiceId = prev.ttsVoiceId,
                    autoReadEnabled = prev.autoReadEnabled,
                    autoCollapseThinking = prev.autoCollapseThinking,
                    hasApiKey = services.hasApiKey(),
                )
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

    private fun liveDraftFor(sessionId: String) =
        services.orchestrator.running.value
            ?.takeIf { it.sessionId == sessionId }
            ?.draft
            ?.value

    /** 新建对话：不立即落库（首条消息发送时才创建会话行，避免空会话） */
    fun newSession() {
        // 必须先停掉正在进行的生成：否则 orchestrator 的流式草稿回调会把旧会话内容
        // 写回刚清空的新会话，表现为「点了新建但内容又回来了」。
        if (_ui.value.isStreaming) services.orchestrator.stopGeneration()
        val prev = _ui.value
        hadStreamingDraft = false
        val emptySessionId = prev.sessionId.takeIf {
            prev.messages.isEmpty() && !prev.isStreaming
        }
        val leftoverAttachments = prev.pendingAttachments
        _ui.value = ChatUiState(
            sessionId = null,
            conversationTitle = str(UiR.string.new_chat),
            draftText = "",
            pendingAttachments = emptyList(),
            thinkingEnabled = prev.thinkingEnabled,
            webSearchEnabled = prev.webSearchEnabled,
            ttsVoiceId = prev.ttsVoiceId,
            autoReadEnabled = prev.autoReadEnabled,
            autoCollapseThinking = prev.autoCollapseThinking,
            hasApiKey = services.hasApiKey(),
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
        val allById = data.roots.associate { it.message.id to it }
        val allNodes = data.roots + data.roots.flatMap { collectDescendants(it) }
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
                blocks = data.blocksByMessageId[msg.id].orEmpty(),
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
            val data = services.repository.loadSession(sessionId) ?: return@launch
            val attachments = services.repository.getAttachments(sessionId)
            _ui.value = _ui.value.copy(
                conversationTitle = data.conversation.title.ifBlank { str(UiR.string.new_chat) },
                messages = toUiMessages(data, attachments),
            )
        }
    }

    // ------------------------------ 输入与草稿 ------------------------------

    fun onDraftChange(text: String) {
        _ui.value = _ui.value.copy(draftText = text)
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

    // ------------------------------ 发送 / 停止 ------------------------------

    /** 发送：上传未上传附件 → 插入用户消息 → 绑定附件 → 发起生成（F02） */
    fun send() {
        val state = _ui.value
        if (!state.canSend) return
        if (!services.hasApiKey()) {
            _ui.value = state.copy(error = AppError(AppError.Kind.AUTH, str(UiR.string.need_api_key)))
            return
        }
        if (state.isStreaming) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 首条消息或附件：先创建会话
                val sessionId = ensureSessionId()
                // 1. 上传附件（未 UPLOADED 的失败附件不入请求）
                val uploadedIds = mutableListOf<String>()
                state.pendingAttachments.forEach { att ->
                    when (att.uploadState) {
                        UploadState.UPLOADED -> uploadedIds += att.serverFileId!!
                        UploadState.LOCAL, UploadState.FAILED -> {
                            val fileId = uploadAttachment(att)
                            uploadedIds += fileId
                        }
                        UploadState.UPLOADING -> {
                            _ui.value = _ui.value.copy(
                                error = AppError(AppError.Kind.LOCAL, "附件仍在上传中，请稍候")
                            )
                            return@launch
                        }
                    }
                }

                val text = state.draftText.trim()
                val now = services.time.nowMillis()

                // 2. 插入用户消息（含文本块）
                val isFirstTurn = state.messages.isEmpty()
                val userMessage = ChatMessage(
                    id = services.time.newId(),
                    sessionId = sessionId,
                    role = "user",
                    parentId = state.messages.lastOrNull()?.message?.id,
                    createdAt = now,
                )
                val userBlocks = if (text.isNotBlank()) listOf<ReplyBlock>(ReplyBlock.Text(text)) else emptyList()
                services.repository.insertMessage(userMessage, userBlocks)
                services.repository.bindAttachmentsToMessage(
                    state.pendingAttachments.map { it.id },
                    userMessage.id,
                )
                services.repository.saveDraft(sessionId, "")

                // 3. 组装请求：活跃路径（不含刚插入的用户消息）+ 新消息内容
                val (path, blocks) = services.repository.activePathWithBlocks(sessionId)
                val history = path.dropLast(1)
                val attByMsg = attachmentsByMessage(sessionId, history)
                val request = RequestFactory.build(
                    history = history,
                    blocksByMessageId = blocks,
                    attachmentsByMessageId = attByMsg,
                    newMessageText = text,
                    newMessageFileIds = uploadedIds,
                    options = RequestFactory.Options(
                        thinkingEnabled = state.thinkingEnabled,
                        webSearchEnabled = state.webSearchEnabled,
                        readUrlEnabled = RequestFactory.containsUrl(text) || state.webSearchEnabled,
                        titleEnabled = isFirstTurn,
                    ),
                )

                _ui.value = _ui.value.copy(
                    draftText = "",
                    pendingAttachments = emptyList(),
                    error = null,
                    retryableMessageId = null,
                )
                reloadMessages()

                services.orchestrator.generate(
                    sessionId = sessionId,
                    parentMessageId = userMessage.id,
                    request = request,
                    model = RequestFactory.MODEL,
                    thinkingEnabled = state.thinkingEnabled,
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    error = AttachmentManager.toAppError(e).let { if (it.kind == AppError.Kind.UNKNOWN) AppError(AppError.Kind.LOCAL, "发送失败：${e.message}") else it }
                )
            }
        }
    }

    /** 一次会话级查询得到“消息 → 已上传 file_id 列表”映射（避免逐消息查库） */
    private suspend fun attachmentsByMessage(sessionId: String, history: List<ChatMessage>): Map<String, List<String>> {
        val historyIds = history.map { it.id }.toSet()
        return services.repository.getAttachments(sessionId)
            .asSequence()
            .filter { it.messageId != null && it.serverFileId != null && it.messageId in historyIds }
            .groupBy({ it.messageId!! }, { it.serverFileId!! })
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
        _ui.value = _ui.value.copy(voiceHint = null)
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
                path.takeWhile { it.id != parentId } + path.firstOrNull { it.id == parentId }!!
            }
            val attByMsg = attachmentsByMessage(sessionId, history)
            val request = RequestFactory.build(
                history = history,
                blocksByMessageId = blocks,
                attachmentsByMessageId = attByMsg,
                newMessageText = "",
                newMessageFileIds = emptyList(),
                options = RequestFactory.Options(
                    thinkingEnabled = _ui.value.thinkingEnabled,
                    webSearchEnabled = _ui.value.webSearchEnabled,
                    readUrlEnabled = _ui.value.webSearchEnabled,
                    titleEnabled = false,
                ),
                appendUserMessage = false,
            )
            services.orchestrator.generate(
                sessionId = sessionId,
                parentMessageId = parentId,
                request = request,
                model = RequestFactory.MODEL,
                thinkingEnabled = _ui.value.thinkingEnabled,
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
                attachmentsByMessageId = attByMsg,
                newMessageText = newText.trim(),
                newMessageFileIds = emptyList(),
                options = RequestFactory.Options(
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
                model = RequestFactory.MODEL,
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
            val all = data.roots + data.roots.flatMap { collectDescendants(it) }
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

    fun addAttachment(uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessionId = ensureSessionId()
                val attachment = services.attachmentManager.importBlocking(uri, sessionId)
                services.repository.insertAttachment(attachment)
                _ui.value = _ui.value.copy(
                    pendingAttachments = _ui.value.pendingAttachments + attachment
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(error = AttachmentManager.toAppError(e))
            }
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
                val err = com.dsh.deepseek.feature.voice.asr.AsrExecutor.toAppError(e)
                services.recordingController.cleanupTempFile()
                val hint = com.dsh.deepseek.feature.voice.asr.AsrExecutor.userFacingHint(err)
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

    /** 生成完成后自动朗读最后一条助手回答（§3.5）：直接查库，避免与异步 reloadMessages 竞态 */
    private fun readLastAssistant() {
        val sessionId = _ui.value.sessionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val data = services.repository.loadSession(sessionId) ?: return@launch
            val last = data.activePath.lastOrNull { it.role == "assistant" } ?: return@launch
            val text = data.blocksByMessageId[last.id].orEmpty()
                .filterIsInstance<ReplyBlock.Text>().joinToString("\n") { it.text }
            if (text.isNotBlank()) {
                services.ttsPlayer.play(last.id, text, _ui.value.ttsVoiceId)
            }
        }
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
