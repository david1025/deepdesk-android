package com.dsh.deepseek.feature.chat.engine

import com.dsh.deepseek.core.common.AppError
import com.dsh.deepseek.core.common.AppLog
import com.dsh.deepseek.core.common.TimeProvider
import com.dsh.deepseek.core.model.ChatMessage
import com.dsh.deepseek.core.model.MessageRequest
import com.dsh.deepseek.core.model.ReplyBlock
import com.dsh.deepseek.core.model.ReplyStatus
import com.dsh.deepseek.core.model.SseEvent
import com.dsh.deepseek.core.model.StopReason
import com.dsh.deepseek.core.network.DeepSeekApiException
import com.dsh.deepseek.core.network.DeepSeekStreamErrorException
import com.dsh.deepseek.core.network.MessageStreamClient
import com.dsh.deepseek.feature.chat.data.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicReference

/**
 * 聊天编排器（§4.2）：
 * - 生成任务独立于 Composable 生命周期（挂靠外部 scope），切换会话不默认取消
 * - SSE 增量在内存合并，按节流策略落盘，避免逐 token 写库
 * - UI 不能以 HTTP 200 判定成功；最终态由 message_stop/refusal/异常共同决定
 */
class ChatOrchestrator(
    private val scope: CoroutineScope,
    private val streamClient: MessageStreamClient,
    private val repository: ChatRepository,
    private val time: TimeProvider,
) {

    private val json = Json { ignoreUnknownKeys = true }

    data class RunningGeneration(
        val sessionId: String,
        val assistantMessageId: String,
        val draft: MutableStateFlow<DraftUi>,
        val job: Job,
    )

    /** 生成中的回复草稿（UI 直接观察；多会话并发互不干扰） */
    data class DraftUi(
        val sessionId: String,
        val assistantMessageId: String,
        val blocks: List<ReplyBlock> = emptyList(),
        val status: ReplyStatus = ReplyStatus.STREAMING,
        val thinking: Boolean = false,
        val usage: com.dsh.deepseek.core.model.UsageInfo? = null,
    )

    private val _running = MutableStateFlow<RunningGeneration?>(null)
    val running: StateFlow<RunningGeneration?> = _running

    private val lastPersist = AtomicReference(0L)

    /** 是否已有该会话在生成 */
    fun isGenerating(sessionId: String): Boolean =
        _running.value?.takeIf { it.sessionId == sessionId } != null

    /**
     * 发起一次生成。调用方（ViewModel）已准备好 user 消息与附件绑定。
     * 不自动重试（断流/错误不重发，避免二次扣费 §12）。
     */
    fun generate(
        sessionId: String,
        parentMessageId: String?, // 通常为刚插入的 user 消息 ID（null = 会话首条）
        request: MessageRequest,
        model: String,
        thinkingEnabled: Boolean,
        /** 继续生成：复用已停止的助手消息，不另开一条。 */
        resumeMessageId: String? = null,
        prefixBlocks: List<ReplyBlock> = emptyList(),
    ) {
        check(_running.value == null) { "已有生成任务进行中" }

        val assistantId = resumeMessageId ?: time.newId()
        val prefix = prefixBlocks
        val now = time.nowMillis()
        val assistantMessage = ChatMessage(
            id = assistantId,
            sessionId = sessionId,
            role = "assistant",
            parentId = parentMessageId,
            selectedChildId = null,
            status = ReplyStatus.STREAMING,
            model = model,
            createdAt = now,
        )
        val draftFlow = MutableStateFlow(
            DraftUi(sessionId, assistantId, prefix, ReplyStatus.STREAMING, thinkingEnabled),
        )

        val job = scope.launch {
            if (resumeMessageId == null) {
                repository.insertMessage(assistantMessage)
            } else {
                repository.updateMessageStatus(assistantId, ReplyStatus.STREAMING)
            }
            val reducer = MessageEventReducer()
            var stoppedByUser = false
            fun shownBlocks() = mergeContinuation(prefix, reducer.snapshot().blocks.map { it.toReplyBlock() })

            val collect = streamClient.stream(request)
            try {
                collect.collect { event ->
                    val changed = reducer.onEvent(event)
                    if (changed) publishDraft(assistantId, sessionId, reducer, thinkingEnabled, prefix)
                    if (changed && shouldPersist()) persistDraft(assistantId, shownBlocks())
                }
            } catch (e: DeepSeekApiException) {
                reducer.onEvent(httpErrorEvent(e))
            } catch (e: DeepSeekStreamErrorException) {
                AppLog.w(TAG, "流内业务错误: ${e.message}")
                reducer.onEvent(
                    SseEvent.Unknown("__failed__", json.encodeToString(FailureCarrier.serializer(), FailureCarrier(null, e.errorType, e.message)))
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                stoppedByUser = true
            } catch (e: Exception) {
                AppLog.e(TAG, "生成异常", e)
                reducer.onEvent(
                    SseEvent.Unknown("__failed__", json.encodeToString(FailureCarrier.serializer(), FailureCarrier(null, null, e.message ?: "请求失败")))
                )
            }

            // 终态判定（§5.3）：完整成功必须收到 message_stop
            val snapshot = reducer.snapshot()
            val finalStatus = when {
                snapshot.refusal != null -> ReplyStatus.REFUSED
                stoppedByUser -> ReplyStatus.STOPPED
                snapshot.failure?.interrupted == true -> ReplyStatus.INTERRUPTED
                snapshot.failure != null -> ReplyStatus.ERROR
                snapshot.finished -> ReplyStatus.DONE
                else -> ReplyStatus.INTERRUPTED // 无 message_stop 也无失败标记：按断流处理
            }

            // 用户取消后仍需完整落盘：挂到 NonCancellable
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                persistFinal(
                    assistantId,
                    sessionId,
                    shownBlocks(),
                    reducer.snapshot(),
                    finalStatus,
                    model,
                    accumulateUsage = resumeMessageId != null,
                )
            }
            _running.value = null
        }

        _running.value = RunningGeneration(sessionId, assistantId, draftFlow, job)
    }

    /** 用户停止生成：取消流；已收到内容保留为 STOPPED（§5.3） */
    fun stopGeneration() {
        val running = _running.value ?: return
        running.job.cancel(kotlinx.coroutines.CancellationException("user_stop"))
    }

    // ------------------------------ 内部 ------------------------------

    private fun publishDraft(
        assistantId: String,
        sessionId: String,
        reducer: MessageEventReducer,
        thinking: Boolean,
        prefix: List<ReplyBlock>,
    ) {
        val snap = reducer.snapshot()
        _running.value?.takeIf { it.assistantMessageId == assistantId }?.draft?.value =
            DraftUi(
                sessionId = sessionId,
                assistantMessageId = assistantId,
                blocks = mergeContinuation(prefix, snap.blocks.map { it.toReplyBlock() }),
                status = ReplyStatus.STREAMING,
                thinking = thinking,
                usage = snap.usage,
            )
    }

    private fun shouldPersist(): Boolean {
        val now = System.currentTimeMillis()
        val last = lastPersist.get()
        if (now - last < PERSIST_INTERVAL_MS) return false
        return lastPersist.compareAndSet(last, now)
    }

    private suspend fun persistDraft(messageId: String, blocks: List<ReplyBlock>) {
        try {
            repository.replaceBlocks(messageId, blocks)
        } catch (e: Exception) {
            AppLog.w(TAG, "中途落盘失败（忽略）: ${e.message}")
        }
    }

    private suspend fun persistFinal(
        messageId: String,
        sessionId: String,
        blocks: List<ReplyBlock>,
        snap: MessageEventReducer.Draft,
        status: ReplyStatus,
        model: String,
        accumulateUsage: Boolean = false,
    ) {
        try {
            repository.replaceBlocks(messageId, blocks)
            repository.updateMessageStatus(messageId, status)
            repository.touchConversation(sessionId)

            snap.usage?.let { usage ->
                repository.recordUsage(
                    sessionId = sessionId,
                    messageId = messageId,
                    model = model,
                    usage = usage,
                    accumulate = accumulateUsage,
                )
            }

            // F10 自动标题（仅用户未手动改名时生效）
            snap.title?.takeIf { it.isNotBlank() }?.let { title ->
                val conversation = repository.getConversation(sessionId)
                if (conversation != null && !conversation.titleManuallyEdited) {
                    repository.autoTitle(sessionId, title, time.nowMillis())
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "终态落盘失败", e)
        }
    }

    private fun httpErrorEvent(e: DeepSeekApiException): SseEvent =
        SseEvent.Unknown(
            "__failed__",
            json.encodeToString(
                FailureCarrier.serializer(),
                FailureCarrier(e.httpStatus, e.errorType, e.message),
            ),
        )

    @kotlinx.serialization.Serializable
    private data class FailureCarrier(
        val status: Int? = null,
        val type: String? = null,
        val message: String? = null,
    )

    private fun MessageEventReducer.DraftBlock.toReplyBlock(): ReplyBlock = when (this) {
        is MessageEventReducer.DraftBlock.Text -> ReplyBlock.Text(text = text.toString(), citations = citations.toList())
        is MessageEventReducer.DraftBlock.Thinking -> ReplyBlock.Thinking(
            text = text.toString(),
            signature = signature,
            running = !closed,
            durationMs = durationMs,
        )
        is MessageEventReducer.DraftBlock.ServerToolUse -> {
            val parsed = runCatching { BlockParsers.json.parseToJsonElement(inputJson.toString()) }.getOrNull()
            val obj = parsed as? kotlinx.serialization.json.JsonObject
            fun s(k: String) = (obj?.get(k) as? kotlinx.serialization.json.JsonPrimitive)?.content
            ReplyBlock.ServerToolUse(
                toolUseId = toolUseId, name = name, kind = kind,
                action = s("action") ?: action,
                query = s("query") ?: query,
                url = s("url") ?: url,
                rawJson = inputJson.toString(),
                running = !closed,
            )
        }
        is MessageEventReducer.DraftBlock.WebSearchResult -> ReplyBlock.WebSearchResult(toolUseId, hits, errorCode)
        is MessageEventReducer.DraftBlock.WebFetchResult -> ReplyBlock.WebFetchResult(toolUseId, url, title, errorCode)
        is MessageEventReducer.DraftBlock.Raw -> ReplyBlock.Raw(type, rawJson)
    }

    companion object {
        private const val TAG = "ChatOrchestrator"
        private const val PERSIST_INTERVAL_MS = 400L
    }
}

/** 继续生成时把新流接到已写出的内容后面。正文首尾相接，不另起一段。 */
internal fun mergeContinuation(prefix: List<ReplyBlock>, incoming: List<ReplyBlock>): List<ReplyBlock> {
    if (incoming.isEmpty()) return prefix
    if (prefix.isEmpty()) return incoming
    val prefixText = prefix.filterIsInstance<ReplyBlock.Text>().joinToString("\n") { it.text }
    val incomingText = incoming.filterIsInstance<ReplyBlock.Text>().joinToString("\n") { it.text }
    if (prefixText.isNotBlank() && incomingText.startsWith(prefixText)) {
        val kept = prefix.takeWhile { it !is ReplyBlock.Text }
        val incomingHasThinking = incoming.any { it is ReplyBlock.Thinking }
        return if (kept.isEmpty() || incomingHasThinking) incoming else kept + incoming
    }
    val last = prefix.last()
    val first = incoming.first()
    if (last is ReplyBlock.Text && first is ReplyBlock.Text) {
        val joined = last.copy(text = last.text + first.text, citations = last.citations + first.citations)
        return prefix.dropLast(1) + joined + incoming.drop(1)
    }
    return prefix + incoming
}
