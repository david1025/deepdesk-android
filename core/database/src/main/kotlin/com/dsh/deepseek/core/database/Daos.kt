package com.dsh.deepseek.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query(
        """
        SELECT id, title, updatedAt, pinned, pinnedAt
        FROM conversations
        ORDER BY pinned DESC, pinnedAt DESC, updatedAt DESC
        """,
    )
    fun observeSummaries(): Flow<List<ConversationSummaryRow>>

    @Query(
        """
        SELECT DISTINCT c.id AS id, c.title AS title, c.updatedAt AS updatedAt,
               c.pinned AS pinned, c.pinnedAt AS pinnedAt
        FROM conversations c
        LEFT JOIN messages m ON m.sessionId = c.id
        LEFT JOIN blocks b ON b.messageId = m.id
        WHERE c.title LIKE '%' || :keyword || '%'
           OR IFNULL(b.text, '') LIKE '%' || :keyword || '%'
        ORDER BY c.pinned DESC, c.pinnedAt DESC, c.updatedAt DESC
        """,
    )
    fun searchSummaries(keyword: String): Flow<List<ConversationSummaryRow>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ConversationEntity>

    @Upsert
    suspend fun upsert(entity: ConversationEntity)

    @Query("UPDATE conversations SET title = :title, titleManuallyEdited = 1, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id AND titleManuallyEdited = 0")
    suspend fun updateAutoTitle(id: String, title: String, updatedAt: Long)

    @Query("UPDATE conversations SET activeMessageId = :messageId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateActiveMessage(id: String, messageId: String?, updatedAt: Long)

    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM conversations WHERE pinned = 1")
    suspend fun countPinned(): Int

    @Query("UPDATE conversations SET pinned = 1, pinnedAt = :pinnedAt WHERE id = :id")
    suspend fun pin(id: String, pinnedAt: Long)

    @Query("UPDATE conversations SET pinned = 0, pinnedAt = 0 WHERE id = :id")
    suspend fun unpin(id: String)
}

data class ConversationSummaryRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val pinnedAt: Long = 0L,
)

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getBySession(sessionId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeBySession(sessionId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE parentId = :parentId ORDER BY createdAt ASC, id ASC")
    suspend fun childrenOf(parentId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestInSession(sessionId: String): MessageEntity?

    @Upsert
    suspend fun upsert(entity: MessageEntity)

    @Upsert
    suspend fun upsertAll(entities: List<MessageEntity>)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String?)

    @Query("UPDATE messages SET selectedChildId = :childId WHERE id = :id")
    suspend fun updateSelectedChild(id: String, childId: String?)

    @Query("UPDATE messages SET edited = :edited WHERE id = :id")
    suspend fun updateEdited(id: String, edited: Boolean)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countInSession(sessionId: String): Int

    @Transaction
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getBySessionWithBlocks(sessionId: String): List<MessageWithBlocks>
}

@Dao
interface BlockDao {

    @Query("SELECT * FROM blocks WHERE messageId = :messageId ORDER BY blockIndex ASC")
    suspend fun getByMessage(messageId: String): List<BlockEntity>

    @Query("SELECT * FROM blocks WHERE messageId IN (:messageIds) ORDER BY messageId ASC, blockIndex ASC")
    suspend fun getByMessages(messageIds: List<String>): List<BlockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(blocks: List<BlockEntity>)

    @Query("DELETE FROM blocks WHERE messageId = :messageId")
    suspend fun deleteByMessage(messageId: String)

    @Transaction
    suspend fun replaceForMessage(messageId: String, blocks: List<BlockEntity>) {
        deleteByMessage(messageId)
        insertAll(blocks)
    }
}

@Dao
interface AttachmentDao {

    @Query("SELECT * FROM attachments WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    suspend fun getBySession(sessionId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observeBySession(sessionId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments WHERE messageId = :messageId")
    suspend fun getByMessage(messageId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments")
    suspend fun getAll(): List<AttachmentEntity>

    @Upsert
    suspend fun upsert(entity: AttachmentEntity)

    @Upsert
    suspend fun upsertAll(entities: List<AttachmentEntity>)

    @Query("UPDATE attachments SET uploadState = :state, serverFileId = :fileId, progressPercent = :progress, errorMessage = :error WHERE id = :id")
    suspend fun updateUpload(id: String, state: String, fileId: String?, progress: Int, error: String?)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun delete(id: String)

    /** 未绑定到任何消息且已过有效期的附件（清理任务使用） */
    @Query("SELECT * FROM attachments WHERE messageId IS NULL AND createdAt < :before")
    suspend fun staleUnbound(before: Long): List<AttachmentEntity>
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): DraftEntity?

    @Upsert
    suspend fun upsert(entity: DraftEntity)

    @Query("DELETE FROM drafts WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}

@Dao
interface ScrollAnchorDao {
    @Query("SELECT * FROM scroll_anchors WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): ScrollAnchorEntity?

    @Upsert
    suspend fun upsert(entity: ScrollAnchorEntity)

    @Query("DELETE FROM scroll_anchors WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}

data class MessageWithBlocks(
    @androidx.room.Embedded val message: MessageEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "messageId")
    val blocks: List<BlockEntity>,
)

@Dao
interface UsageDao {

    @Upsert
    suspend fun upsert(entity: UsageRecordEntity)

    @Query("SELECT * FROM usage_records WHERE sessionId = :sessionId")
    fun observeBySession(sessionId: String): Flow<List<UsageRecordEntity>>

    @Query(
        """
        SELECT dayKey AS dayKey,
               COUNT(*) AS requestCount,
               SUM(cacheHitTokens) AS cacheHitTokens,
               SUM(cacheMissTokens) AS cacheMissTokens,
               SUM(outputTokens) AS outputTokens,
               SUM(webSearchRequests) AS webSearchRequests,
               SUM(estimatedCostCnyMicros) AS estimatedCostCnyMicros,
               COALESCE(SUM(CASE WHEN isPeak = 1 THEN 1 ELSE 0 END), 0) AS peakRequestCount,
               COALESCE(SUM(CASE WHEN isPeak = 0 THEN 1 ELSE 0 END), 0) AS offPeakRequestCount
        FROM usage_records
        GROUP BY dayKey
        ORDER BY dayKey DESC
        """,
    )
    fun observeDaily(): Flow<List<DailyUsageRow>>

    @Query("DELETE FROM usage_records")
    suspend fun deleteAll()
}

data class DailyUsageRow(
    val dayKey: String,
    val requestCount: Int,
    val cacheHitTokens: Long,
    val cacheMissTokens: Long,
    val outputTokens: Long,
    val webSearchRequests: Long,
    val estimatedCostCnyMicros: Long,
    val peakRequestCount: Int = 0,
    val offPeakRequestCount: Int = 0,
)
