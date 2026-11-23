package com.david.deepdesk.feature.chat.data

import androidx.room.withTransaction
import com.david.deepdesk.core.common.TimeProvider
import com.david.deepdesk.core.database.AppDatabase
import com.david.deepdesk.core.database.AttachmentDao
import com.david.deepdesk.core.database.AttachmentEntity
import com.david.deepdesk.core.database.BlockDao
import com.david.deepdesk.core.database.BlockEntity
import com.david.deepdesk.core.database.ConversationDao
import com.david.deepdesk.core.database.ConversationEntity
import com.david.deepdesk.core.database.ConversationSummaryRow
import com.david.deepdesk.core.database.DraftDao
import com.david.deepdesk.core.database.DraftEntity
import com.david.deepdesk.core.database.MessageDao
import com.david.deepdesk.core.database.MessageEntity
import com.david.deepdesk.core.database.ScrollAnchorDao
import com.david.deepdesk.core.database.ScrollAnchorEntity
import com.david.deepdesk.core.database.UsageDao
import com.david.deepdesk.core.database.UsageRecordEntity
import com.david.deepdesk.core.database.DailyUsageRow
import com.david.deepdesk.core.model.ApiProtocol
import com.david.deepdesk.core.model.Attachment
import com.david.deepdesk.core.model.ChatMessage
import com.david.deepdesk.core.model.Conversation
import com.david.deepdesk.core.model.ReplyBlock
import com.david.deepdesk.core.model.ReplyStatus
import com.david.deepdesk.core.model.TokenAccounting
import com.david.deepdesk.core.model.TokenPricing
import com.david.deepdesk.core.model.TokenUsageTotals
import com.david.deepdesk.core.model.UploadState
import com.david.deepdesk.core.model.UsageInfo
import com.david.deepdesk.feature.chat.engine.BlockJsonCodec
import com.david.deepdesk.feature.chat.engine.BlockParsers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId

/**
 * 会话/消息/分支的持久化仓库（§6.2 Room 存储规则）。
 * 分支 = 消息树（parentId + selectedChildId）；编辑历史问题不改旧答案。
 * 会话与消息写库在同一事务内完成，不产生孤立消息。
 */
class ChatRepository(
    private val db: AppDatabase,
    private val time: TimeProvider,
) {
    private val conversationDao: ConversationDao = db.conversationDao()
    private val messageDao: MessageDao = db.messageDao()
    private val blockDao: BlockDao = db.blockDao()
    val attachmentDao: AttachmentDao = db.attachmentDao()
    private val draftDao: DraftDao = db.draftDao()
    private val anchorDao: ScrollAnchorDao = db.scrollAnchorDao()
    private val usageDao: UsageDao = db.usageDao()

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------ 会话 ------------------------------

    fun observeConversations(): Flow<List<ConversationSummaryRow>> = conversationDao.observeSummaries()

    fun searchConversations(keyword: String): Flow<List<ConversationSummaryRow>> =
        conversationDao.searchSummaries(keyword)

    suspend fun createConversation(): Conversation {
        val now = time.nowMillis()
        val entity = ConversationEntity(
            id = time.newId(),
            title = "",
            createdAt = now,
            updatedAt = now,
            rootMessageId = null,
            activeMessageId = null,
        )
        conversationDao.upsert(entity)
        return entity.toDomain()
    }

    suspend fun getConversation(id: String): Conversation? = conversationDao.get(id)?.toDomain()

    suspend fun rename(id: String, title: String) {
        conversationDao.rename(id, title.trim(), time.nowMillis())
    }

    /** 置顶；已置顶直接成功。达到上限返回 false。 */
    suspend fun pin(id: String): Boolean {
        val existing = conversationDao.get(id) ?: return false
        if (existing.pinned) return true
        if (conversationDao.countPinned() >= MAX_PINNED) return false
        conversationDao.pin(id, time.nowMillis())
        return true
    }

    suspend fun unpin(id: String) {
        conversationDao.unpin(id)
    }

    /** 自动标题：不触碰 titleManuallyEdited 标记（F10） */
    suspend fun autoTitle(id: String, title: String, updatedAt: Long) {
        conversationDao.updateAutoTitle(id, title.trim(), updatedAt)
    }

    suspend fun delete(id: String) {
        db.withTransaction { conversationDao.delete(id) }
    }

    suspend fun deleteAll() = conversationDao.deleteAll()

    suspend fun getDraft(sessionId: String): String? = draftDao.get(sessionId)?.text

    suspend fun saveDraft(sessionId: String, text: String) {
        if (text.isBlank()) draftDao.delete(sessionId) else draftDao.upsert(DraftEntity(sessionId, text, time.nowMillis()))
    }

    suspend fun saveScrollAnchor(sessionId: String, anchorMessageId: String?, offset: Int) {
        anchorDao.upsert(ScrollAnchorEntity(sessionId, anchorMessageId, offset, time.nowMillis()))
    }

    suspend fun getScrollAnchor(sessionId: String): ScrollAnchorEntity? = anchorDao.get(sessionId)

    suspend fun touchConversation(id: String) = conversationDao.touch(id, time.nowMillis())

    // ------------------------------ 消息树 ------------------------------

    class Node(
        val message: ChatMessage,
        val blocks: List<ReplyBlock>,
        val children: MutableList<Node> = mutableListOf(),
    )

    data class SessionData(
        val conversation: Conversation,
        val activePath: List<ChatMessage>,
        val blocksByMessageId: Map<String, List<ReplyBlock>>,
        val roots: List<Node>,
    )

    /**
     * 活跃分支：有 activeMessageId 时从该叶子沿 parent 走到根。
     * 否则从最新根节点出发，优先 selectedChildId，没有再取最新子节点。
     */
    fun buildActivePath(all: List<Node>, activeMessageId: String? = null): List<ChatMessage> {
        val byId = all.associateBy { it.message.id }
        val leaf = activeMessageId?.let { byId[it] }
        if (leaf != null) {
            val chain = ArrayDeque<ChatMessage>()
            var current: Node? = leaf
            val seen = HashSet<String>()
            while (current != null && seen.add(current.message.id)) {
                chain.addFirst(current.message)
                current = current.message.parentId?.let { byId[it] }
            }
            return chain.toList()
        }
        val roots = all.filter { it.message.parentId == null }.sortedBy { it.message.createdAt }
        val path = mutableListOf<ChatMessage>()
        var current: Node? = roots.lastOrNull() ?: return emptyList()
        val seen = HashSet<String>()
        while (current != null && seen.add(current.message.id)) {
            path.add(current.message)
            current = current.message.selectedChildId?.let { byId[it] } ?: current.children.lastOrNull()
        }
        return path
    }

    /** 加载会话全部消息和块；会话不存在时返回 null。 */
    suspend fun loadSession(sessionId: String): SessionData? {
        val (conversation, rows) = db.withTransaction {
            val conversation = conversationDao.get(sessionId) ?: return@withTransaction null
            conversation to messageDao.getBySessionWithBlocks(sessionId)
        } ?: return null
        val nodes = rows.map { row ->
            Node(
                ChatMessage(
                    id = row.message.id,
                    sessionId = row.message.sessionId,
                    role = row.message.role,
                    parentId = row.message.parentId,
                    selectedChildId = row.message.selectedChildId,
                    status = row.message.status?.let { runCatching { ReplyStatus.valueOf(it) }.getOrNull() },
                    model = row.message.model,
                    createdAt = row.message.createdAt,
                    edited = row.message.edited,
                ),
                row.blocks.map { it.toDomain() },
            )
        }
        val byId = nodes.associateBy { it.message.id }
        nodes.forEach { node ->
            node.message.parentId?.let { parentId -> byId[parentId]?.children?.add(node) }
        }
        nodes.forEach { it.children.sortBy { c -> c.message.createdAt } }
        val activePath = buildActivePath(nodes, conversation.activeMessageId)
        val blocks = nodes.associate { it.message.id to it.blocks }
        return SessionData(conversation.toDomain(), activePath, blocks, nodes)
    }

    /** 组装请求用的活跃分支（消息 + 块） */
    suspend fun activePathWithBlocks(sessionId: String): Pair<List<ChatMessage>, Map<String, List<ReplyBlock>>> {
        val data = loadSession(sessionId) ?: return emptyList<ChatMessage>() to emptyMap()
        return data.activePath to data.blocksByMessageId
    }

    /** 原子写入新消息（含分支指向与活跃叶子更新） */
    suspend fun insertMessage(message: ChatMessage, blocks: List<ReplyBlock> = emptyList()) {
        db.withTransaction {
            messageDao.upsert(
                MessageEntity(
                    id = message.id, sessionId = message.sessionId, role = message.role,
                    parentId = message.parentId, selectedChildId = message.selectedChildId,
                    status = message.status?.name, model = message.model,
                    createdAt = message.createdAt, edited = message.edited,
                )
            )
            if (blocks.isNotEmpty()) {
                blockDao.replaceForMessage(message.id, blocks.mapIndexed { i, b -> b.toEntity(message.id, i) })
            }
            message.parentId?.let { parentId ->
                messageDao.updateSelectedChild(parentId, message.id)
            }
            conversationDao.updateActiveMessage(message.sessionId, message.id, time.nowMillis())
        }
    }

    suspend fun updateMessage(message: ChatMessage) = messageDao.upsert(
        MessageEntity(
            id = message.id, sessionId = message.sessionId, role = message.role,
            parentId = message.parentId, selectedChildId = message.selectedChildId,
            status = message.status?.name, model = message.model,
            createdAt = message.createdAt, edited = message.edited,
        )
    )

    suspend fun updateMessageStatus(id: String, status: ReplyStatus) = messageDao.updateStatus(id, status.name)

    suspend fun replaceBlocks(messageId: String, blocks: List<ReplyBlock>) {
        blockDao.replaceForMessage(messageId, blocks.mapIndexed { i, b -> b.toEntity(messageId, i) })
    }

    /** 分支切换：父消息指向指定子节点 */
    suspend fun selectChild(parentId: String?, childId: String, sessionId: String) {
        db.withTransaction {
            parentId?.let { messageDao.updateSelectedChild(it, childId) }
            val messages = messageDao.getBySession(sessionId)
            val leafId = selectedBranchLeaf(messages, childId) ?: return@withTransaction
            conversationDao.updateActiveMessage(sessionId, leafId, time.nowMillis())
        }
    }

    /** 进程重启恢复：未完结流标记异常中断（§6.2），不自动重发 */
    suspend fun markInterrupted(sessionId: String) {
        val messages = messageDao.getBySession(sessionId)
        messages.filter {
            it.status == ReplyStatus.STREAMING.name || it.status == ReplyStatus.PENDING.name
        }.forEach { messageDao.updateStatus(it.id, ReplyStatus.INTERRUPTED.name) }
    }

    suspend fun markAllSessionsInterrupted() {
        conversationDao.getAll().forEach { markInterrupted(it.id) }
    }

    // ------------------------------ 附件 ------------------------------

    suspend fun insertAttachment(attachment: Attachment) = attachmentDao.upsert(attachment.toEntity())

    suspend fun updateAttachmentUpload(id: String, state: UploadState, fileId: String?, progress: Int, error: String?) =
        attachmentDao.updateUpload(id, state.name, fileId, progress, error)

    suspend fun getAttachments(sessionId: String): List<Attachment> =
        attachmentDao.getBySession(sessionId).map { it.toDomain() }

    suspend fun getAttachment(id: String): Attachment? = attachmentDao.get(id)?.toDomain()

    suspend fun bindAttachmentsToMessage(attachmentIds: List<String>, messageId: String) {
        attachmentIds.forEach { id ->
            attachmentDao.get(id)?.let { attachmentDao.upsert(it.copy(messageId = messageId)) }
        }
    }

    suspend fun deleteAttachment(id: String) = attachmentDao.delete(id)

    suspend fun getAllAttachments(): List<Attachment> = attachmentDao.getAll().map { it.toDomain() }

    // ------------------------------ 用量 ------------------------------

    suspend fun recordUsage(
        sessionId: String,
        messageId: String,
        model: String?,
        usage: UsageInfo,
        createdAt: Long = time.nowMillis(),
        /** 继续生成：同一条回复再记一笔，累加到原用量上，避免覆盖前半段。 */
        accumulate: Boolean = false,
    ) {
        val breakdown = TokenAccounting.from(usage)
        if (breakdown.isEmpty) return
        val cnyMicros = TokenPricing.cnyToMicros(TokenPricing.estimateCny(breakdown, model, createdAt))
        val existing = if (accumulate) usageDao.getByMessage(messageId) else null
        if (existing != null) {
            usageDao.upsert(
                existing.copy(
                    cacheHitTokens = existing.cacheHitTokens + breakdown.cacheHitTokens,
                    cacheMissTokens = existing.cacheMissTokens + breakdown.cacheMissTokens,
                    outputTokens = existing.outputTokens + breakdown.outputTokens,
                    webSearchRequests = existing.webSearchRequests + breakdown.webSearchRequests,
                    estimatedCostCnyMicros = existing.estimatedCostCnyMicros + cnyMicros,
                    isPeak = existing.isPeak || TokenPricing.isPeak(createdAt),
                ),
            )
            return
        }
        val dayKey = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        usageDao.upsert(
            UsageRecordEntity(
                id = messageId,
                sessionId = sessionId,
                messageId = messageId,
                model = model,
                createdAt = createdAt,
                dayKey = dayKey,
                cacheHitTokens = breakdown.cacheHitTokens,
                cacheMissTokens = breakdown.cacheMissTokens,
                outputTokens = breakdown.outputTokens,
                webSearchRequests = breakdown.webSearchRequests,
                estimatedCostCnyMicros = cnyMicros,
                isPeak = TokenPricing.isPeak(createdAt),
            ),
        )
    }

    fun observeSessionUsage(sessionId: String): Flow<TokenUsageTotals> =
        usageDao.observeBySession(sessionId).map { rows ->
            TokenUsageTotals(
                cacheHitTokens = rows.sumOf { it.cacheHitTokens.toLong() },
                cacheMissTokens = rows.sumOf { it.cacheMissTokens.toLong() },
                outputTokens = rows.sumOf { it.outputTokens.toLong() },
                webSearchRequests = rows.sumOf { it.webSearchRequests.toLong() },
                estimatedCostCnyMicros = rows.sumOf { it.estimatedCostCnyMicros },
                requestCount = rows.size,
                hasPeak = rows.any { it.isPeak },
                hasOffPeak = rows.any { !it.isPeak },
            )
        }

    fun observeDailyUsage(): Flow<List<DailyUsageRow>> = usageDao.observeDaily()

    suspend fun clearUsage() = usageDao.deleteAll()

    // ------------------------------ 映射 ------------------------------

    private fun ConversationEntity.toDomain() = Conversation(
        id = id, title = title, createdAt = createdAt, updatedAt = updatedAt,
        rootMessageId = rootMessageId, activeMessageId = activeMessageId,
        titleManuallyEdited = titleManuallyEdited,
        pinned = pinned, pinnedAt = pinnedAt,
    )

    private fun BlockEntity.toDomain(): ReplyBlock = when (type) {
        "text" -> ReplyBlock.Text(
            text = text ?: "",
            citations = BlockJsonCodec.decodeCitations(citationsJson, json),
        )
        "thinking" -> ReplyBlock.Thinking(
            text = text ?: "",
            signature = signature,
            durationMs = durationMs,
        )
        "server_tool_use" -> ReplyBlock.ServerToolUse(
            toolUseId = toolUseId ?: "",
            name = name ?: "",
            kind = BlockParsers.toolKind(name ?: ""),
            action = BlockJsonCodec.toolInputField(inputJson, "action", json),
            query = BlockJsonCodec.toolInputField(inputJson, "query", json),
            url = BlockJsonCodec.toolInputField(inputJson, "url", json),
            rawJson = inputJson ?: "",
            running = false,
        )
        "web_search_tool_result" -> {
            val (hits, errorCode) = BlockJsonCodec.decodeSearchResult(rawJson, json)
            ReplyBlock.WebSearchResult(toolUseId ?: "", hits, errorCode)
        }
        "web_fetch_tool_result" -> {
            val (url, title, errorCode) = BlockJsonCodec.decodeFetchResult(rawJson, json)
            ReplyBlock.WebFetchResult(toolUseId ?: "", url, title, errorCode)
        }
        else -> ReplyBlock.Raw(type, rawJson ?: "")
    }

    private fun ReplyBlock.toEntity(messageId: String, index: Int): BlockEntity = when (this) {
        is ReplyBlock.Text -> BlockEntity(
            messageId = messageId, blockIndex = index, type = "text",
            text = text, citationsJson = BlockJsonCodec.encodeCitations(citations, json),
        )
        is ReplyBlock.Thinking -> BlockEntity(
            messageId = messageId, blockIndex = index, type = "thinking",
            text = text, signature = signature, durationMs = durationMs,
        )
        is ReplyBlock.ServerToolUse -> BlockEntity(
            messageId = messageId, blockIndex = index, type = "server_tool_use",
            toolUseId = toolUseId, name = name, inputJson = rawJson,
        )
        is ReplyBlock.WebSearchResult -> BlockEntity(
            messageId = messageId, blockIndex = index, type = "web_search_tool_result",
            toolUseId = toolUseId, rawJson = BlockJsonCodec.encodeSearchResult(hits, errorCode, json),
        )
        is ReplyBlock.WebFetchResult -> BlockEntity(
            messageId = messageId, blockIndex = index, type = "web_fetch_tool_result",
            toolUseId = toolUseId, rawJson = BlockJsonCodec.encodeFetchResult(url, title, errorCode, json),
        )
        is ReplyBlock.Raw -> BlockEntity(
            messageId = messageId, blockIndex = index, type = type, rawJson = rawJson,
        )
    }

    private fun AttachmentEntity.toDomain() = Attachment(
        id = id, sessionId = sessionId, messageId = messageId,
        localPath = localPath, localUri = localUri, serverFileId = serverFileId,
        mimeType = mimeType, displayName = displayName, sizeBytes = sizeBytes,
        purpose = purpose,
        apiProtocol = runCatching { ApiProtocol.valueOf(apiProtocol) }.getOrDefault(ApiProtocol.ANTHROPIC_FILES),
        uploadState = runCatching { UploadState.valueOf(uploadState) }.getOrDefault(UploadState.LOCAL),
        isImage = isImage, createdAt = createdAt,
    )

    private fun Attachment.toEntity() = AttachmentEntity(
        id = id, sessionId = sessionId, messageId = messageId,
        localPath = localPath, localUri = localUri, serverFileId = serverFileId,
        mimeType = mimeType, displayName = displayName, sizeBytes = sizeBytes,
        purpose = purpose, apiProtocol = apiProtocol.name, uploadState = uploadState.name,
        isImage = isImage, createdAt = createdAt, progressPercent = progressPercent,
    )

    companion object {
        const val MAX_PINNED = 10
    }
}

/** Follow the saved choice at each fork, falling back to the newest child. */
internal fun selectedBranchLeaf(messages: List<MessageEntity>, childId: String): String? {
    val byId = messages.associateBy { it.id }
    val childrenByParent = messages.groupBy { it.parentId }
    var current = byId[childId] ?: return null
    val seen = mutableSetOf<String>()
    while (seen.add(current.id)) {
        val children = childrenByParent[current.id].orEmpty()
        val next = children.firstOrNull { it.id == current.selectedChildId }
            ?: children.lastOrNull()
            ?: break
        if (next.id in seen) break
        current = next
    }
    return current.id
}
