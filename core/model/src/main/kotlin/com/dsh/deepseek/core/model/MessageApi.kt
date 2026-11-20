package com.dsh.deepseek.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Messages API 请求/响应契约（官方 create-message 文档，2026-11-17 核对）。
 * POST https://api.deepseek.com/anthropic/v1/messages，x-api-key + SSE。
 */

@Serializable
data class MessageRequest(
    val model: String,
    val messages: List<ApiMessage>,
    val system: String? = null,
    val thinking: ThinkingConfig? = null,
    val tools: List<ToolSpec>? = null,
    @SerialName("safety_check") val safetyCheck: SafetyCheck? = null,
    val title: TitleConfig? = null,
    val stream: Boolean = true,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
data class ThinkingConfig(val type: String) {
    companion object {
        val ENABLED = ThinkingConfig("enabled")
        val DISABLED = ThinkingConfig("disabled")
    }
}

@Serializable
data class ToolSpec(val type: String, val name: String) {
    companion object {
        val WEB_SEARCH = ToolSpec("deepseek_web_search", "deepseek_web_search")
        val READ_URL = ToolSpec("deepseek_read_url", "deepseek_read_url")
    }
}

@Serializable
data class SafetyCheck(val level: String) {
    companion object {
        val HIGH = SafetyCheck("high")
    }
}

@Serializable
data class TitleConfig(val enabled: Boolean)

/** 消息内容：纯文本或块数组（文本 + file_id 引用），序列化为 JsonElement 以兼容两种形态 */
@Serializable
data class ApiMessage(
    val role: String,
    val content: JsonElement,
) {
    companion object {
        fun user(text: String): ApiMessage = ApiMessage("user", kotlinx.serialization.json.JsonPrimitive(text))
        fun assistant(text: String): ApiMessage = ApiMessage("assistant", kotlinx.serialization.json.JsonPrimitive(text))
    }
}

/** 请求内用户消息的 file 引用块（文档等非图片，与文本块拼成 content 数组） */
@Serializable
data class FileRefBlock(
    val type: String = "file",
    @SerialName("file_id") val fileId: String,
)

/**
 * Anthropic Messages 的图片块。file_id 必须放在 source 里，不能用顶层 type=file，
 * 否则接口直接拒绝，客户端会把它显示成网络中断。
 */
@Serializable
data class ImageFileBlock(
    val type: String = "image",
    val source: ImageFileSource,
)

@Serializable
data class ImageFileSource(
    val type: String = "file",
    @SerialName("file_id") val fileId: String,
)

@Serializable
data class TextBlock(val type: String = "text", val text: String)

// ---------------------------------------------------------------------------
// SSE 事件（message_start / content_block_start / content_block_delta /
// content_block_stop / message_delta / message_stop / ping / error）
// ---------------------------------------------------------------------------

@Serializable
sealed interface SseEvent {

    @Serializable
    @SerialName("message_start")
    data class MessageStart(
        val message: MessageStartInfo? = null,
        /** lenient：自动标题可能随事件下发（联调确认） */
        val title: String? = null,
    ) : SseEvent

    @Serializable
    @SerialName("content_block_start")
    data class ContentBlockStart(
        val index: Int,
        /** 各块类型结构不同，保留原始 JSON，由 Reducer 解析 */
        @SerialName("content_block") val block: JsonElement,
    ) : SseEvent

    @Serializable
    @SerialName("content_block_delta")
    data class ContentBlockDelta(
        val index: Int,
        val delta: JsonElement,
    ) : SseEvent

    @Serializable
    @SerialName("content_block_stop")
    data class ContentBlockStop(val index: Int) : SseEvent

    @Serializable
    @SerialName("message_delta")
    data class MessageDelta(
        val delta: MessageDeltaInfo? = null,
        val usage: UsageInfo? = null,
        /** lenient：title 可能挂在顶层或 delta 内 */
        val title: String? = null,
    ) : SseEvent

    @Serializable
    @SerialName("message_stop")
    data class MessageStop(val title: String? = null) : SseEvent

    @Serializable
    @SerialName("ping")
    data class Ping(val title: String? = null) : SseEvent

    @Serializable
    @SerialName("error")
    data class ErrorEvent(val error: ApiErrorBody? = null, val title: String? = null) : SseEvent

    /** 未知事件兜底：保留原始数据，不破坏流 */
    @Serializable
    @SerialName("__unknown__")
    data class Unknown(val rawType: String, val rawJson: String) : SseEvent
}

@Serializable
data class MessageStartInfo(
    val id: String? = null,
    val model: String? = null,
    val role: String? = null,
    val usage: UsageInfo? = null,
    val title: String? = null,
)

@Serializable
data class MessageDeltaInfo(
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null,
    @SerialName("stop_details") val stopDetails: StopDetails? = null,
    val title: String? = null,
)

@Serializable
data class StopDetails(
    val type: String? = null,
    val category: String? = null,
    val explanation: String? = null,
)

@Serializable
data class UsageInfo(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("cache_read_input_tokens") val cacheReadTokens: Int? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationTokens: Int? = null,
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Int? = null,
    @SerialName("prompt_cache_miss_tokens") val promptCacheMissTokens: Int? = null,
    @SerialName("server_tool_use") val serverToolUse: ServerToolUsage? = null,
)

@Serializable
data class ServerToolUsage(
    @SerialName("web_search_requests") val webSearchRequests: Int? = null,
    val tool_search: Int? = null,
    @SerialName("web_fetch_requests") val webFetchRequests: Int? = null,
    val tool_fetch: Int? = null,
)

@Serializable
data class ApiErrorBody(
    val type: String? = null,
    val message: String? = null,
)

/** HTTP 错误响应信封：{"type":"error","error":{...}} */
@Serializable
data class ApiErrorEnvelope(
    val type: String? = null,
    val error: ApiErrorBody? = null,
)

// ---------------------------------------------------------------------------
// Delta / Block 结构的解析模型（由 Reducer 从 JsonElement 中提取）
// ---------------------------------------------------------------------------

/** content_block_delta.delta 的已知类型 */
sealed interface BlockDelta {
    data class Text(val text: String) : BlockDelta
    data class Thinking(val thinking: String) : BlockDelta
    data class Signature(val signature: String) : BlockDelta
    data class InputJson(val partialJson: String) : BlockDelta
    data class Citation(val citation: Citation) : BlockDelta
}

/** content_block_start.content_block 的已知类型 */
sealed interface BlockStart {
    data class Text(val citations: List<Citation>) : BlockStart
    data class Thinking(val thinking: String, val signature: String?) : BlockStart
    data class ServerToolUse(
        val toolUseId: String,
        val name: String,
        val rawJson: String,
    ) : BlockStart

    data class WebSearchResult(
        val toolUseId: String,
        val hits: List<SearchHit>,
        val errorCode: String?,
    ) : BlockStart

    data class WebFetchResult(
        val toolUseId: String,
        val url: String?,
        val title: String?,
        val errorCode: String?,
    ) : BlockStart
}

/** SSE 协议中 message_delta 的最终 stop_reason 取值 */
object StopReason {
    const val END_TURN = "end_turn"
    const val MAX_TOKENS = "max_tokens"
    const val STOP_SEQUENCE = "stop_sequence"
    const val TOOL_USE = "tool_use"
    const val REFUSAL = "refusal"
}
