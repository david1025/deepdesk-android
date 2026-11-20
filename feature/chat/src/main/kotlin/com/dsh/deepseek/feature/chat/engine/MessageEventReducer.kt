package com.dsh.deepseek.feature.chat.engine

import com.dsh.deepseek.core.model.Citation
import com.dsh.deepseek.core.model.SearchHit
import com.dsh.deepseek.core.model.ServerToolKind
import com.dsh.deepseek.core.model.SseEvent
import com.dsh.deepseek.core.model.StopReason
import com.dsh.deepseek.core.model.StopDetails
import com.dsh.deepseek.core.model.UsageInfo

/**
 * SSE 事件规约器（§5.3）：
 * 按 index 增量合并块，输出可持久化/可渲染的中间草稿。
 * 关键语义：
 * - message_stop = 成功终态；缺失则视 interrupted
 * - stop_reason=refusal → 拒答（UI 需替换已暂显内容）
 * - 用户停止生成（取消流）由 Orchestrator 标记，Reducer 不感知
 */
class MessageEventReducer {

    /** 单个块的构建态 */
    sealed interface DraftBlock {
        val id: Int

        data class Text(
            override val id: Int,
            val text: StringBuilder,
            val citations: MutableList<Citation> = mutableListOf(),
            val closed: Boolean = false,
        ) : DraftBlock

        data class Thinking(
            override val id: Int,
            val text: StringBuilder,
            var signature: String? = null,
            var closed: Boolean = false,
            val startedAt: Long = System.currentTimeMillis(),
            var durationMs: Long? = null,
        ) : DraftBlock

        data class ServerToolUse(
            override val id: Int,
            val toolUseId: String,
            val name: String,
            val kind: ServerToolKind,
            val action: String?,
            val query: String?,
            val url: String?,
            val inputJson: StringBuilder,
            var closed: Boolean = false,
        ) : DraftBlock

        data class WebSearchResult(
            override val id: Int,
            val toolUseId: String,
            val hits: List<SearchHit>,
            val errorCode: String?,
        ) : DraftBlock

        data class WebFetchResult(
            override val id: Int,
            val toolUseId: String,
            val url: String?,
            val title: String?,
            val errorCode: String?,
        ) : DraftBlock

        data class Raw(override val id: Int, val type: String, val rawJson: String) : DraftBlock
    }

    /** HTTP 错误 / 断流载体（由 MessageStreamClient 的特殊 Unknown 事件传递） */
    data class StreamFailure(
        val httpStatus: Int? = null,
        val errorType: String? = null,
        val message: String? = null,
        val requestId: String? = null,
        val interrupted: Boolean = false,
    )

    data class Refusal(val category: String?, val explanation: String?)

    data class Draft(
        val blocks: List<DraftBlock> = emptyList(),
        val stopReason: String? = null,
        val stopDetails: StopDetails? = null,
        val usage: UsageInfo? = null,
        val title: String? = null,
        val refusal: Refusal? = null,
        val messageId: String? = null,
        val failure: StreamFailure? = null,
        /** 收到 message_stop */
        val finished: Boolean = false,
    ) {
        /** 按协议判定：必须收到 message_stop 才算完整成功（§2.2 原则 4） */
        val isComplete: Boolean get() = finished && failure == null
    }

    private val blocksById = linkedMapOf<Int, DraftBlock>()
    private var nextLocalId = 1

    private var stopReason: String? = null
    private var stopDetails: StopDetails? = null
    private var usage: UsageInfo? = null
    private var title: String? = null
    private var refusal: Refusal? = null
    private var serverMessageId: String? = null
    private var failure: StreamFailure? = null
    private var finished = false

    /** 消费一个事件，返回是否改变了草稿内容（用于节流落盘判定） */
    fun onEvent(event: SseEvent): Boolean = when (event) {
        is SseEvent.MessageStart -> {
            serverMessageId = event.message?.id ?: serverMessageId
            event.message?.title?.let { if (title == null) title = it }
            event.title?.let { if (title == null) title = it }
            usage = event.message?.usage ?: usage
            true
        }

        is SseEvent.ContentBlockStart -> {
            val parsed = BlockParsers.parseBlockStart(event.block)
            when (parsed) {
                is BlockParsers.ParsedBlock.Text ->
                    blocksById[event.index] = DraftBlock.Text(event.index, StringBuilder(parsed.text), parsed.citations.toMutableList())
                is BlockParsers.ParsedBlock.Thinking ->
                    blocksById[event.index] = DraftBlock.Thinking(event.index, StringBuilder(parsed.thinking), parsed.signature)
                is BlockParsers.ParsedBlock.ServerToolUse ->
                    blocksById[event.index] = DraftBlock.ServerToolUse(
                        event.index, parsed.toolUseId, parsed.name, parsed.kind,
                        parsed.action, parsed.query, parsed.url,
                        // input_json_delta 将流式拼接完整 JSON；start 的 {} 占位不参与拼接
                        StringBuilder(""),
                    )
                is BlockParsers.ParsedBlock.WebSearchResult ->
                    blocksById[event.index] = DraftBlock.WebSearchResult(event.index, parsed.toolUseId, parsed.hits, parsed.errorCode)
                is BlockParsers.ParsedBlock.WebFetchResult ->
                    blocksById[event.index] = DraftBlock.WebFetchResult(event.index, parsed.toolUseId, parsed.url, parsed.title, parsed.errorCode)
                is BlockParsers.ParsedBlock.Citations -> {
                    // 独立 citations 块：并入最近的文本块
                    val lastText = blocksById.values.lastOrNull { it is DraftBlock.Text } as? DraftBlock.Text
                    if (lastText != null) lastText.citations.addAll(parsed.citations)
                }
                is BlockParsers.ParsedBlock.Unknown ->
                    blocksById[event.index] = DraftBlock.Raw(event.index, parsed.type, parsed.rawJson)
            }
            true
        }

        is SseEvent.ContentBlockDelta -> {
            when (val delta = BlockParsers.parseDelta(event.delta)) {
                is BlockParsers.ParsedDelta.Text ->
                    (blocksById[event.index] as? DraftBlock.Text)?.text?.append(delta.text)
                is BlockParsers.ParsedDelta.Thinking ->
                    (blocksById[event.index] as? DraftBlock.Thinking)?.text?.append(delta.thinking)
                is BlockParsers.ParsedDelta.Signature ->
                    (blocksById[event.index] as? DraftBlock.Thinking)?.let { it.signature = delta.signature }
                is BlockParsers.ParsedDelta.InputJson ->
                    (blocksById[event.index] as? DraftBlock.ServerToolUse)?.inputJson?.append(delta.partialJson)
                is BlockParsers.ParsedDelta.ParsedCitation ->
                    (blocksById[event.index] as? DraftBlock.Text)?.citations?.add(delta.citation)
                BlockParsers.ParsedDelta.Unknown -> Unit
            }
            true
        }

        is SseEvent.ContentBlockStop -> {
            when (val b = blocksById[event.index]) {
                is DraftBlock.Text -> blocksById[event.index] = b.copy(closed = true)
                is DraftBlock.Thinking -> blocksById[event.index] = b.copy(
                    closed = true,
                    durationMs = b.durationMs
                        ?: (System.currentTimeMillis() - b.startedAt).coerceAtLeast(0L),
                )
                is DraftBlock.ServerToolUse -> {
                    b.closed = true
                    // input_json_delta 完整 JSON 解析出的 query/url 补充展示字段
                    val parsedInput = parseToolInput(b.inputJson.toString())
                    blocksById[event.index] = b.copy(
                        action = parsedInput.first ?: b.action,
                        query = parsedInput.second ?: b.query,
                        url = parsedInput.third ?: b.url,
                    )
                }
                else -> Unit
            }
            true
        }

        is SseEvent.MessageDelta -> {
            stopReason = event.delta?.stopReason ?: stopReason
            stopDetails = event.delta?.stopDetails ?: stopDetails
            event.usage?.let { usage = it }
            event.delta?.title?.let { title = it }
            event.title?.let { title = it }
            if (stopReason == StopReason.REFUSAL) {
                refusal = Refusal(stopDetails?.category, stopDetails?.explanation)
            }
            true
        }

        is SseEvent.MessageStop -> {
            finished = true
            event.title?.let { title = it }
            true
        }

        is SseEvent.Ping -> false // 心跳

        is SseEvent.ErrorEvent -> {
            failure = StreamFailure(
                errorType = event.error?.type,
                message = event.error?.message,
                interrupted = false,
            )
            true
        }

        is SseEvent.Unknown -> when (event.rawType) {
            "__interrupted__" -> {
                failure = StreamFailure(interrupted = true, message = "连接中断")
                true
            }
            "__failed__" -> {
                failure = StreamFailure(message = event.rawJson.ifBlank { "请求失败" })
                true
            }
            "__http_error__" -> {
                failure = parseHttpErrorCarrier(event.rawJson)
                true
            }
            else -> false // 其余未知事件忽略
        }
    }

    private fun parseHttpErrorCarrier(raw: String): StreamFailure {
        return runCatching {
            val el = BlockParsers.json.parseToJsonElement(raw)
            val obj = el as? kotlinx.serialization.json.JsonObject ?: return StreamFailure(message = raw)
            fun s(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            StreamFailure(
                httpStatus = s("status")?.toIntOrNull(),
                errorType = s("type"),
                message = s("message"),
                requestId = s("request_id"),
            )
        }.getOrElse { StreamFailure(message = raw) }
    }

    /** 解析 server_tool_use 完整 input：{action, query, url, ...} */
    private fun parseToolInput(json: String): Triple<String?, String?, String?> {
        if (json.isBlank()) return Triple(null, null, null)
        return runCatching {
            val obj = BlockParsers.json.parseToJsonElement(json) as? kotlinx.serialization.json.JsonObject
                ?: return@runCatching Triple<String?, String?, String?>(null, null, null)
            fun s(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            Triple(s("action"), s("query"), s("url"))
        }.getOrDefault(Triple(null, null, null))
    }

    fun snapshot(): Draft = Draft(
        blocks = blocksById.values.toList(),
        stopReason = stopReason,
        stopDetails = stopDetails,
        usage = usage,
        title = title,
        refusal = refusal,
        messageId = serverMessageId,
        failure = failure,
        finished = finished,
    )
}
