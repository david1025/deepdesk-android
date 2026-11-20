package com.dsh.deepseek.core.model

/**
 * 领域模型（设计文档 §6.1）。
 * Room 实体见 core/database；本层不依赖任何框架。
 */

/** 回复状态机（§6.3）：NEW -> PENDING -> STREAMING -> DONE / STOPPED / ERROR / INTERRUPTED */
enum class ReplyStatus { PENDING, STREAMING, DONE, STOPPED, ERROR, INTERRUPTED, REFUSED }

data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val rootMessageId: String? = null,
    val activeMessageId: String? = null,
    /** true 表示标题由用户手动修改，自动标题不得覆盖（F10） */
    val titleManuallyEdited: Boolean = false,
    val pinned: Boolean = false,
    val pinnedAt: Long = 0L,
)

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: String, // user / assistant
    val parentId: String? = null,
    val selectedChildId: String? = null,
    val status: ReplyStatus? = null,
    val model: String? = null,
    val createdAt: Long = 0L,
    /** 编辑后新增的分支标记，用于 UI 展示“已编辑” */
    val edited: Boolean = false,
)

data class Citation(
    val url: String,
    val title: String? = null,
    val citedText: String? = null,
    val encryptedIndex: String? = null,
)

data class SearchHit(
    val title: String? = null,
    val url: String,
    val snippet: String? = null,
    val siteName: String? = null,
    val siteIcon: String? = null,
    val pageAge: String? = null,
)

/** 服务端工具块种类（对应 content_block type） */
enum class ServerToolKind { WEB_SEARCH, WEB_FETCH, UNKNOWN }

sealed interface ReplyBlock {
    /** 正文文本块，携带来自联网搜索/链接阅读的引用 */
    data class Text(
        val text: String,
        val citations: List<Citation> = emptyList(),
        /** 引用角标在文本中的编号（1-based，与 citations 下标对应） */
    ) : ReplyBlock

    /** 思考块：流式更新、可折叠；signature 需保留（协议要求） */
    data class Thinking(
        val text: String,
        val signature: String? = null,
        val running: Boolean = false,
        /** 思考耗时，流式结束时写入；未结束为 null */
        val durationMs: Long? = null,
    ) : ReplyBlock

    /** server_tool_use：模型正在调用内置搜索/读链接 */
    data class ServerToolUse(
        val toolUseId: String,
        val name: String,
        val kind: ServerToolKind,
        /** 搜索动作类型：search / open_page / find_in_page / read_link */
        val action: String? = null,
        val query: String? = null,
        val url: String? = null,
        val rawJson: String,
        val running: Boolean = true,
    ) : ReplyBlock

    /** web_search_tool_result：搜索结果列表或错误码 */
    data class WebSearchResult(
        val toolUseId: String,
        val hits: List<SearchHit> = emptyList(),
        val errorCode: String? = null,
    ) : ReplyBlock

    /** web_fetch_tool_result：链接阅读结果（正文 data 恒为空，read_link 正文在 signature 中） */
    data class WebFetchResult(
        val toolUseId: String,
        val url: String? = null,
        val title: String? = null,
        val errorCode: String? = null,
    ) : ReplyBlock

    /** 未知/扩展块兜底：保留原始 JSON，避免丢数据 */
    data class Raw(val type: String, val rawJson: String) : ReplyBlock
}

enum class UploadState { LOCAL, UPLOADING, UPLOADED, FAILED }

enum class ApiProtocol { ANTHROPIC_FILES, STANDARD_FILES }

data class Attachment(
    val id: String,
    val sessionId: String,
    val messageId: String? = null,
    val localPath: String? = null,
    val localUri: String? = null,
    val serverFileId: String? = null,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val purpose: String? = null,
    val apiProtocol: ApiProtocol = ApiProtocol.ANTHROPIC_FILES,
    val uploadState: UploadState = UploadState.LOCAL,
    val isImage: Boolean = false,
    val createdAt: Long = 0L,
    /** 上传进度 0..100，仅 UPLOADING 状态有意义 */
    val progressPercent: Int = 0,
    val errorMessage: String? = null,
)

/** 会话列表摘要 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val pinnedAt: Long = 0L,
)
