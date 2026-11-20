package com.david.deepdesk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 独立工具 API 契约（ASR / TTS / OCR / Search / Fetch / Files）。
 * 认证：Bearer（区别于 /anthropic 端点的 x-api-key）。
 */

// ------------------------------ 模型列表（F01 验证） ------------------------------

@Serializable
data class ModelsResponse(val data: List<ModelInfo> = emptyList())

@Serializable
data class ModelInfo(
    val id: String? = null,
    val `object`: String? = null,
    val owned_by: String? = null,
)

// ------------------------------ 账户余额 GET /user/balance ------------------------------

@Serializable
data class UserBalanceResponse(
    @SerialName("is_available") val isAvailable: Boolean? = null,
    @SerialName("balance_infos") val balanceInfos: List<BalanceInfo> = emptyList(),
)

@Serializable
data class BalanceInfo(
    val currency: String? = null,
    @SerialName("total_balance") val totalBalance: String? = null,
    @SerialName("granted_balance") val grantedBalance: String? = null,
    @SerialName("topped_up_balance") val toppedUpBalance: String? = null,
)

// ------------------------------ Files（F07/F08） ------------------------------

@Serializable
data class FileUploadResponse(
    val id: String,
    val filename: String? = null,
    val bytes: Long? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val purpose: String? = null,
    @SerialName("created_at") val createdAt: JsonElement? = null,
    @SerialName("expires_at") val expiresAt: JsonElement? = null,
)

@Serializable
data class FileDeleteResponse(
    val id: String? = null,
    val deleted: Boolean? = null,
    val type: String? = null,
)

// ------------------------------ ASR（F11） ------------------------------

@Serializable
data class AsrResponse(
    @SerialName("request_id") val requestId: String? = null,
    val text: String? = null,
    @SerialName("duration_ms") val durationMs: Long? = null,
)

// ------------------------------ TTS（F12） ------------------------------

@Serializable
data class TtsRequest(
    val text: String,
    @SerialName("voice_id") val voiceId: String = "stella",
    val format: String = "pcm",
    @SerialName("filter_markdown") val filterMarkdown: Boolean = true,
) {
    companion object {
        val VOICES = listOf("stella", "tide")
    }
}

// ------------------------------ OCR（F14） ------------------------------

@Serializable
data class OcrResponse(
    @SerialName("request_id") val requestId: String? = null,
    /** lenient：官方响应为逐页文本；未知字段忽略 */
    val pages: List<OcrPage> = emptyList(),
    val text: String? = null,
    val error: JsonElement? = null,
) {
    val isBusinessError: Boolean get() = error != null
    val plainText: String
        get() = when {
            !text.isNullOrBlank() -> text
            pages.isNotEmpty() -> pages.mapIndexed { i, p ->
                buildString {
                    if (pages.size > 1) append("[第 ${p.pageNo ?: (i + 1)} 页]\n")
                    append(p.text ?: "")
                }
            }.joinToString("\n\n")
            else -> ""
        }
}

@Serializable
data class OcrPage(
    @SerialName("page_no") val pageNo: Int? = null,
    val page: Int? = null,
    val text: String? = null,
    val content: String? = null,
)

// ------------------------------ Search / Fetch（F15） ------------------------------

@Serializable
data class SearchRequest(
    val query: String,
    val count: Int? = null,
    /** lenient：未来扩展字段 */
    val extra: JsonElement? = null,
)

@Serializable
data class SearchApiResponse(
    @SerialName("request_id") val requestId: String? = null,
    /** 官方响应结构以联调为准；保留原始 JSON 用于诊断页展示 */
    val data: JsonElement? = null,
    val results: JsonElement? = null,
    val error: JsonElement? = null,
) {
    val isBusinessError: Boolean get() = error != null
}

@Serializable
data class FetchRequest(
    val url: String,
    val extra: JsonElement? = null,
)

@Serializable
data class FetchApiResponse(
    @SerialName("request_id") val requestId: String? = null,
    val url: String? = null,
    val title: String? = null,
    val content: JsonElement? = null,
    val text: String? = null,
    val error: JsonElement? = null,
) {
    val isBusinessError: Boolean get() = error != null
}
