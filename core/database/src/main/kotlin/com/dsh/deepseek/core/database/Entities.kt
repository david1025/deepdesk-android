package com.dsh.deepseek.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room 实体（设计文档 §6.2）。
 * 会话/消息/块/附件以稳定 ID 关联；删除会话级联删除消息与块；附件独立清理。
 */

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val rootMessageId: String?,
    val activeMessageId: String?,
    /** 用户手动改过标题后，自动标题不再覆盖（F10） */
    val titleManuallyEdited: Boolean = false,
    /** 抽屉置顶（官方上限 10） */
    val pinned: Boolean = false,
    val pinnedAt: Long = 0L,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId"), Index("parentId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String, // user / assistant
    val parentId: String?,
    val selectedChildId: String?,
    val status: String?, // ReplyStatus.name，仅 assistant 有值
    val model: String?,
    val createdAt: Long,
    val edited: Boolean = false,
)

/** 回复块：一个 assistant 消息可有多个块（思考/正文/工具），顺序由 blockIndex 决定 */
@Entity(
    tableName = "blocks",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("messageId")],
)
data class BlockEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val messageId: String,
    val blockIndex: Int,
    val type: String, // text / thinking / server_tool_use / web_search_tool_result / web_fetch_tool_result / raw
    /** text/thinking 增量合并后的正文 */
    val text: String? = null,
    /** thinking signature（协议要求保留） */
    val signature: String? = null,
    val toolUseId: String? = null,
    val name: String? = null,
    /** 工具 input 原始 JSON（server_tool_use） */
    val inputJson: String? = null,
    /** 结构化结果的原始 JSON（web_search_tool_result / web_fetch_tool_result / raw） */
    val rawJson: String? = null,
    /** text 块的引用列表 JSON */
    val citationsJson: String? = null,
    /** thinking 块耗时（毫秒），流结束时写入 */
    val durationMs: Long? = null,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("sessionId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String?,
    val localPath: String?,
    val localUri: String?,
    val serverFileId: String?,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val purpose: String?,
    val apiProtocol: String, // ANTHROPIC_FILES / STANDARD_FILES
    val uploadState: String, // UploadState.name
    val isImage: Boolean = false,
    val createdAt: Long,
    /** 最近一次上传进度（0..100），仅 UPLOADING 状态有意义 */
    val progressPercent: Int = 0,
    /** 上传失败原因（本地预检或服务端错误文案） */
    val errorMessage: String? = null,
)

/** 每会话一份未发送草稿（§6.2 草稿规则） */
@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val sessionId: String,
    val text: String,
    val updatedAt: Long,
)

/** 滚动锚点：记录首条可见消息 ID + 偏移，避免插入消息导致绝对 index 错位（§6.2） */
@Entity(tableName = "scroll_anchors")
data class ScrollAnchorEntity(
    @PrimaryKey val sessionId: String,
    val anchorMessageId: String?,
    val anchorOffset: Int,
    val updatedAt: Long,
)

/**
 * 本地用量流水：按助手消息落一条。不级联删除会话，便于删除对话后仍保留每日统计。
 */
@Entity(
    tableName = "usage_records",
    indices = [Index("sessionId"), Index("dayKey"), Index(value = ["messageId"], unique = true)],
)
data class UsageRecordEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val messageId: String?,
    val model: String?,
    val createdAt: Long,
    /** 本地日历日 yyyy-MM-dd */
    val dayKey: String,
    val cacheHitTokens: Int,
    val cacheMissTokens: Int,
    val outputTokens: Int,
    val webSearchRequests: Int,
    /** 估算费用，人民币元 × 1e6 */
    val estimatedCostCnyMicros: Long,
    /** 写入时按北京时间高峰窗口判定，历史行由 MIGRATION_4_5 按 createdAt 回填。 */
    val isPeak: Boolean = false,
)
